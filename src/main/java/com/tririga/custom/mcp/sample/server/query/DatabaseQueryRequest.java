package com.tririga.custom.mcp.sample.server.query;

import com.fasterxml.jackson.annotation.JsonProperty;


public class DatabaseQueryRequest {

    @JsonProperty("scriptName")
    private String scriptName;

    @JsonProperty("description")
    private String description;

 
    @JsonProperty("sqlQuery")
    private String sqlQuery;


    @JsonProperty("limit")
    private Integer limit = 100;

    public DatabaseQueryRequest() {
    }

    public DatabaseQueryRequest(String scriptName, String description, String sqlQuery, Integer limit) {
        this.scriptName = scriptName;
        this.description = description;
        this.sqlQuery = sqlQuery;
        this.limit = limit;
    }

    public String getScriptName() {
        return scriptName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSqlQuery() {
        return sqlQuery;
    }

    public void setSqlQuery(String sqlQuery) {
        this.sqlQuery = sqlQuery;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String scriptName;
        private String description;
        private String sqlQuery;
        private Integer limit = 100;

        public Builder scriptName(String scriptName) {
            this.scriptName = scriptName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder sqlQuery(String sqlQuery) {
            this.sqlQuery = sqlQuery;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public DatabaseQueryRequest build() {
            return new DatabaseQueryRequest(scriptName, description, sqlQuery, limit);
        }
    }
}
