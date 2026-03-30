package com.tririga.custom.mcp.sample.server.model;

public enum StepType {
    START(1, "Start"),
    USER_ACTION(2, "User Action"),
    SYSTEM(3, "System"),
    APPROVAL(4, "Approval"),
    MESSAGE(5, "Message"),
    RESERVE(6, "Reserve"),
    FLOW(7, "Flow"),
    EXCEPTION(8, "Exception"),
    END(9, "End"),
    END_OF_FORK(10, "End of Fork"),
    END_OF_ITERATION(11, "End of Iteration"),
    END_OF_SWITCH_LOOP(12, "End of Switch & Loop"),
    FORK(13, "Fork"),
    SWITCH(14, "Switch"),
    CASE(15, "Case"),
    CHILD_WORKFLOW(16, "Child Workflow"),
    SCHEDULE(17, "Schedule"),
    NO_OP(18, "No-op"),
    STOP(19, "Stop"),
    LOOP(20, "Loop"),
    BREAK(21, "Break"),
    QUERY(22, "Query"),
    MODIFY_METADATA(23, "Modify Metadata"),
    ITERATOR(24, "Iterator"),
    GET_TEMP_RECORD(25, "Get Temp Record"),
    SAVE_TEMP_TO_PERM(26, "Save Temp to Perm"),
    CREATE_RECORD(27, "Create Record"),
    MODIFY_RECORDS(28, "Modify Records"),
    RETRIEVE_RECORDS(29, "Retrieve Records"),
    ASSOCIATE_RECORDS(30, "Associate Records"),
    TRIGGER_ACTION(31, "Trigger Action"),
    DELETE_REFERENCE(32, "Delete Reference"),
    ADD_CHILD(33, "Add Child"),
    SET_PROJECT(34, "Set Project"),
    ATTACH_FORMAT_FILE(35, "Attach Format File"),
    POPULATE_FILE(36, "Populate File"),
    DISTILL_FILE(37, "Distill File"),
    CALL_WORKFLOW(38, "Call Workflow"),
    CUSTOM(39, "Custom"),
    VARIABLE_DEFINITION(40, "Variable Definition"),
    VARIABLE_ASSIGNMENT(41, "Variable Assignment"),
    DATACONNECT(42, "DataConnect"),
    FACT_CONDITION(43, "Fact Condition");

    private final int id;
    private final String label;

    StepType(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public int getId() { return id; }
    public String getLabel() { return label; }

    // Helper method to get the label based on an integer ID
    public static String getLabelById(int id) {
        for (StepType type : values()) {
            if (type.id == id) {
                return type.label;
            }
        }
        return "Unknown"; // Fallback
    }
}