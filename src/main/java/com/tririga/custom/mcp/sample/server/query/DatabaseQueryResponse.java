package com.tririga.custom.mcp.sample.server.query;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class DatabaseQueryResponse {

    @JsonProperty("name")
    private String name;

    @JsonProperty("script")
    private String script;

    @JsonProperty("description")
    private String description;

    @JsonProperty("createdBy")
    private Long createdBy;

    @JsonProperty("createdDate")
    private Long createdDate;

    @JsonProperty("updatedBy")
    private Long updatedBy;

    @JsonProperty("updatedDate")
    private Long updatedDate;

    @JsonProperty("lastExecutedBy")
    private Long lastExecutedBy;

    @JsonProperty("lastExecutedDate")
    private Long lastExecutedDate;

    @JsonProperty("status")
    private String status;

    @JsonProperty("exception")
    private String exception;

    @JsonProperty("limit")
    private Integer limit;

    @JsonProperty("metaDataHelper")
    private MetaDataHelper metaDataHelper;

    @JsonProperty("results")
    private List<List<ResultValue>> results;

    @JsonProperty("new")
    private Boolean isNew;

    @JsonProperty("scriptStripped")
    private String scriptStripped;

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Long createdDate) {
        this.createdDate = createdDate;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Long updatedDate) {
        this.updatedDate = updatedDate;
    }

    public Long getLastExecutedBy() {
        return lastExecutedBy;
    }

    public void setLastExecutedBy(Long lastExecutedBy) {
        this.lastExecutedBy = lastExecutedBy;
    }

    public Long getLastExecutedDate() {
        return lastExecutedDate;
    }

    public void setLastExecutedDate(Long lastExecutedDate) {
        this.lastExecutedDate = lastExecutedDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getException() {
        return exception;
    }

    public void setException(String exception) {
        this.exception = exception;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public MetaDataHelper getMetaDataHelper() {
        return metaDataHelper;
    }

    public void setMetaDataHelper(MetaDataHelper metaDataHelper) {
        this.metaDataHelper = metaDataHelper;
    }

    public List<List<ResultValue>> getResults() {
        return results;
    }

    public void setResults(List<List<ResultValue>> results) {
        this.results = results;
    }

    public Boolean getIsNew() {
        return isNew;
    }

    public void setIsNew(Boolean isNew) {
        this.isNew = isNew;
    }

    public String getScriptStripped() {
        return scriptStripped;
    }

    public void setScriptStripped(String scriptStripped) {
        this.scriptStripped = scriptStripped;
    }

    public static class MetaDataHelper {
        @JsonProperty("colCount")
        private Integer colCount;

        @JsonProperty("columnLabels")
        private Map<String, String> columnLabels;

        @JsonProperty("columnClassTypes")
        private Map<String, String> columnClassTypes;

        public Integer getColCount() {
            return colCount;
        }

        public void setColCount(Integer colCount) {
            this.colCount = colCount;
        }

        public Map<String, String> getColumnLabels() {
            return columnLabels;
        }

        public void setColumnLabels(Map<String, String> columnLabels) {
            this.columnLabels = columnLabels;
        }

        public Map<String, String> getColumnClassTypes() {
            return columnClassTypes;
        }

        public void setColumnClassTypes(Map<String, String> columnClassTypes) {
            this.columnClassTypes = columnClassTypes;
        }
    }

    public static class ResultValue {
        @JsonProperty("value")
        private Object value;

        @JsonProperty("clazz")
        private String clazz;

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public String getClazz() {
            return clazz;
        }

        public void setClazz(String clazz) {
            this.clazz = clazz;
        }
    }
}