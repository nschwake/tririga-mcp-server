package com.tririga.custom.mcp.sample.server.model;

import java.util.Objects;

public class WorkflowTracingStep {
    private  String taskLabel;
    private final String wfName;
    private final Integer type;
    private final Integer workflowStepID;
    private final Integer parentWorkflowStepID;
    private final Integer wfTemplteId;


    public void setTaskLabel(String taskLabel) {
        this.taskLabel = taskLabel;
    }
    public String getWfName() {
        return wfName;
    }
    public void setConditional(boolean isConditional) {
        this.isConditional = isConditional;
    }
    private  boolean isConditional;
    private  String conditionExpression;
    private  Integer filterObject;
    private String calledWFName;

    public String getCalledWFName() {
        return calledWFName;
    }
    public void setCalledWFName(String calledWFName) {
        this.calledWFName = calledWFName;
    }
    public Integer getFilterObject() {
        return filterObject;
    }
    public void setFilterObject(Integer filterObject) {
        this.filterObject = filterObject;
    }
    public String getConditionExpression() {
        return conditionExpression;
    }
    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }
    public WorkflowTracingStep(String name, Integer type, Integer workflowStepID, Integer parentWorkflowStepID, Integer wfTemplteId, String wfName) {
         this.taskLabel = name;
         this.wfName = wfName; this.type = type; this.workflowStepID = workflowStepID;
        this.parentWorkflowStepID = parentWorkflowStepID;
        this.wfTemplteId = wfTemplteId; 
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(hashCode()+": "+wfName+" "+"'"+taskLabel+"'"+" "+type+" "+workflowStepID+" "+parentWorkflowStepID+" "+parentHashCode());
        if(type == StepType.CALL_WORKFLOW.getId()){
            sb.append(" "+calledWFName);
        }
        if(type == StepType.SWITCH.getId()){
            sb.append(" "+conditionExpression);
        }
        return sb.toString(); 
        }
    // JGraphT uses hashCode/equals to identify nodes
    @Override
    public boolean equals(Object o) { return o instanceof WorkflowTracingStep 
        && wfTemplteId.equals(((WorkflowTracingStep) o).wfTemplteId) 
        //&& type.equals(((WorkflowTracingStep) o).type) 
        && workflowStepID.equals(((WorkflowTracingStep) o).workflowStepID) 
        //&& parentWorkflowStepID.equals(((WorkflowTracingStep) o).parentWorkflowStepID) 
        ; 
    }
    @Override
    public int hashCode() { return Objects.hash(wfTemplteId,workflowStepID); }
    
    public int parentHashCode() { return Objects.hash(wfTemplteId,parentWorkflowStepID); }

    public void setIsConditional(boolean x) {this.isConditional = x;}
    public boolean getIsConditional(){return this.isConditional;}
    public Integer getParentWorkflowStepID() {
        return parentWorkflowStepID;
    }
    public String getTaskLabel() {
        return taskLabel;
    }
    public Integer getType() {
        return type;
    }
    public Integer getWorkflowStepID() {
        return workflowStepID;
    }
    public Integer getWfTemplteId() {
        return wfTemplteId;
    }
    
    

}