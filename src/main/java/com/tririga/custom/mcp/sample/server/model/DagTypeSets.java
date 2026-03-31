package com.tririga.custom.mcp.sample.server.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum DagTypeSets {
    WF_CALL_FLOW(new ArrayList<>(Arrays.asList(
        StepType.START.getId(),
        StepType.SWITCH.getId(),
        StepType.CALL_WORKFLOW.getId()
    )), "WF_CALL_FLOW"),

     WF_CALL_FLOW_DETAILED(new ArrayList<>(Arrays.asList(
        StepType.START.getId(),
        StepType.SWITCH.getId(),
        StepType.CALL_WORKFLOW.getId(),
        StepType.END_OF_SWITCH_LOOP.getId(),
        StepType.END.getId()
    )), "WF_CALL_FLOW_DETAILED");
   

    private final List<Integer> idList;
    private final String label;

    DagTypeSets(List<Integer> idList, String label) {
        this.idList = idList;
        this.label = label;
    }

    public List<Integer> getIdList() { return idList; }
    public String getLabel() { return label; }

    // Helper method to get the label based on an integer ID
    public static List<Integer> getIdListByLabel(String label) {
        for (DagTypeSets type : values()) {
            if (type.label == label) {
                return type.idList;
            }
        }
        return new ArrayList<>(); //  
    }
}