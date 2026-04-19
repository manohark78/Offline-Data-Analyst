// nlp/AggregateFunction.java
package com.enterprise.dataanalyst.nlp;

public enum AggregateFunction {
    AVG, SUM, MAX, MIN, COUNT_DISTINCT;

    public String toSqlFunction() {
        return this == COUNT_DISTINCT ? "COUNT(DISTINCT %s)" : this.name();
    }
}