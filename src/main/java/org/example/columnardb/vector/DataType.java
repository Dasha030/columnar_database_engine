package org.example.columnardb.vector;

public enum DataType {
    INT64, FLOAT64, STRING;

    public boolean isNumeric() {
        return this != STRING;
    }
}
