package com.xeats.backend.events;

/** Public change types carried in the SSE event field. */
public enum LiveEventType {
    ORDER_STATUS_CHANGED,
    ITEM_AVAILABILITY_CHANGED,
    COUNTER_ADMISSION_CHANGED,
    OPENING_HOURS_CHANGED,
    SLOT_CAPACITY_CHANGED
}
