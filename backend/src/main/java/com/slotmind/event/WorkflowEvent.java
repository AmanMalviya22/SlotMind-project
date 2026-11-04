package com.slotmind.event;

import com.slotmind.enums.TriggerReason;

public record WorkflowEvent(TriggerReason reason, String subjectId) {
}
