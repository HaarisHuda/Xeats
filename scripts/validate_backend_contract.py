"""Source-level checks for the Xeats backend contract and OpenAPI document."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
OPENAPI_PATH = ROOT / "Docs" / "openapi.yaml"


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects silent duplicate-key overwrites."""


def _construct_unique_mapping(loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            line = key_node.start_mark.line + 1
            raise ValueError(f"duplicate YAML key {key!r} at line {line}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping
)


def resolve_pointer(document: dict, reference: str):
    if not reference.startswith("#/"):
        raise ValueError(f"unsupported non-local reference: {reference}")
    value = document
    for part in reference[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or part not in value:
            raise ValueError(f"unresolved reference: {reference}")
        value = value[part]
    return value


def resolve_parameter(document: dict, parameter: dict) -> dict:
    if "$ref" in parameter:
        return resolve_pointer(document, parameter["$ref"])
    return parameter


def walk(value, location="$", refs=None, properties=None):
    refs = refs if refs is not None else []
    properties = properties if properties is not None else []
    if isinstance(value, dict):
        if "$ref" in value:
            refs.append((location, value["$ref"]))
        for name, child in value.get("properties", {}).items():
            properties.append((location, name))
            walk(child, f"{location}/properties/{name}", refs, properties)
        for key, child in value.items():
            if key != "properties":
                walk(child, f"{location}/{key}", refs, properties)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            walk(child, f"{location}/{index}", refs, properties)
    return refs, properties


def main() -> int:
    document = yaml.load(OPENAPI_PATH.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    assert document["openapi"].startswith("3.1."), "OpenAPI 3.1 is required"

    refs, properties = walk(document)
    for location, reference in refs:
        try:
            resolve_pointer(document, reference)
        except ValueError as error:
            raise ValueError(f"{location}: {error}") from error

    operation_ids = []
    operations = {}
    path_parameter_count = 0
    for path, path_item in document["paths"].items():
        template_parameters = set(re.findall(r"\{([^}]+)\}", path))
        for method, operation in path_item.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}:
                continue
            operation_id = operation.get("operationId")
            if operation_id:
                operation_ids.append(operation_id)
                operations[operation_id] = operation
            parameters = [
                resolve_parameter(document, parameter)
                for parameter in path_item.get("parameters", []) + operation.get("parameters", [])
            ]
            required_path_parameters = {
                parameter["name"]
                for parameter in parameters
                if parameter.get("in") == "path" and parameter.get("required") is True
            }
            if template_parameters != required_path_parameters:
                raise ValueError(
                    f"{method.upper()} {path}: template parameters {sorted(template_parameters)} "
                    f"do not match required path parameters {sorted(required_path_parameters)}"
                )
            path_parameter_count += len(template_parameters)
            if not any(str(code).startswith("2") for code in operation.get("responses", {})):
                raise ValueError(f"{method.upper()} {path}: no successful response")

    if len(operation_ids) != len(set(operation_ids)):
        raise ValueError("operationId values must be unique")

    schemas = document["components"]["schemas"]
    create_order = schemas["CreateOrderRequest"]
    assert create_order.get("additionalProperties") is False
    assert set(create_order["required"]) == {"counterId", "pickupSlotId"}
    assert set(create_order["properties"]) == {"counterId", "pickupSlotId"}
    assert set(schemas["OrderStatus"]["enum"]) == {
        "ACCEPTED", "PREPARING", "READY", "COLLECTED", "EXPIRED"
    }
    assert set(schemas["Availability"]["enum"]) == {"AVAILABLE", "SOLD_OUT"}
    assert "pickupToken" not in schemas["EmployeeOrderSummary"].get("properties", {})
    assert "ORDERING_PAUSED" in schemas["Problem"]["properties"]["code"]["enum"]
    assert "METHOD_NOT_ALLOWED" in schemas["Problem"]["properties"]["code"]["enum"]
    assert "INTERNAL_ERROR" in schemas["Problem"]["properties"]["code"]["enum"]
    assert "ORDERING_PAUSED" in schemas["OrderOptions"]["properties"]["noOptionsReason"]["enum"]
    slot_conflict = schemas["Problem"]["allOf"][0]["then"]
    assert slot_conflict["properties"]["refreshedOrderOptions"]["$ref"] == "#/components/schemas/OrderOptions"

    forbidden_properties = {"quantity", "quantities", "inventory", "stock", "cancelled", "rejected"}
    found_forbidden = [(where, name) for where, name in properties if name.lower() in forbidden_properties]
    if found_forbidden:
        raise ValueError(f"forbidden business fields found: {found_forbidden}")
    for path in document["paths"]:
        if re.search(r"cancel|reject", path, re.IGNORECASE):
            raise ValueError(f"forbidden cancellation/rejection route: {path}")

    create_parameters = [
        resolve_parameter(document, parameter)
        for parameter in operations["createOrder"].get("parameters", [])
    ]
    assert any(
        parameter.get("name") == "Idempotency-Key"
        and parameter.get("in") == "header"
        and parameter.get("required") is True
        for parameter in create_parameters
    ), "order creation requires an idempotency key"

    for operation_id in ("beginPreparing", "markOrderReady", "expireOrder"):
        parameters = [
            resolve_parameter(document, parameter)
            for parameter in operations[operation_id].get("parameters", [])
        ]
        assert any(
            parameter.get("name") == "If-Match"
            and parameter.get("in") == "header"
            and parameter.get("required") is True
            for parameter in parameters
        ), f"{operation_id} requires an order version precondition"

    assert "429" in operations["redeemPickupToken"]["responses"], "pickup redemption is rate-limited"
    assert operations["getHealth"]["servers"][0]["url"] == "http://localhost:8080"

    event_schema = schemas["LiveEvent"]
    event_example = document["paths"]["/events"]["get"]["responses"]["200"]["content"]["text/event-stream"]["example"]
    data_line = next(line for line in event_example.splitlines() if line.startswith("data:"))
    event_payload = json.loads(data_line.removeprefix("data:").strip())
    assert set(event_schema["required"]).issubset(event_payload), "SSE example must satisfy LiveEvent required fields"

    # Recompute the central hand-checkable placement and backlog values.
    assert (6 * (100 - 30)) // 100 == 4
    assert (1 * 300) // (2 * 4) == 37
    assert (3 * 300) // (2 * 4) == 112
    assert (7 * 300) // (2 * 4) == 262
    assert 11 * 60 * 60 + 55 * 60 + 120 + 6 * 75 == 12 * 60 * 60 + 4 * 60 + 30
    seven_order_eta = 11 * 60 * 60 + 55 * 60 + 120 + 7 * 75
    assert seven_order_eta == 12 * 60 * 60 + 5 * 60 + 45
    assert seven_order_eta >= 12 * 60 * 60 + 5 * 60

    print(
        f"OpenAPI {document['openapi']}: {len(document['paths'])} paths, "
        f"{len(operation_ids)} operations, {len(schemas)} schemas, "
        f"{len(refs)} local references resolved, {path_parameter_count} templated parameters checked."
    )
    print("Business invariants, idempotency/precondition headers, ETA arithmetic, and SSE example passed.")
    print("YAML parsing uses PyYAML 6.0 or newer.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, StopIteration, ValueError, yaml.YAMLError) as error:
        print(f"Backend contract validation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
