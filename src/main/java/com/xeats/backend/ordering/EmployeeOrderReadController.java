package com.xeats.backend.ordering;

import java.util.UUID;
import java.util.List;

import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrder;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrderPage;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/orders")
public class EmployeeOrderReadController {
    private final EmployeeOrderReadService orders;

    public EmployeeOrderReadController(EmployeeOrderReadService orders) {
        this.orders = orders;
    }

    @GetMapping
    public EmployeeOrderPage listMyOrders(
            @RequestParam(name = "status", required = false) List<OrderStatus> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            Authentication authentication) {
        XeatsPrincipal employee = (XeatsPrincipal) authentication.getPrincipal();
        return orders.list(employee.userId(), statuses, page, size);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<EmployeeOrder> getMyOrder(
            @PathVariable UUID orderId, Authentication authentication) {
        XeatsPrincipal employee = (XeatsPrincipal) authentication.getPrincipal();
        EmployeeOrder order = orders.get(employee.userId(), orderId);
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"%d\"".formatted(order.version()));
        return ResponseEntity.ok().headers(headers).body(order);
    }
}
