package org.example.columnardb;

/** Єдиний тип помилок рушія: некоректний вхід, пошкоджені буфери, типові невідповідності (ціль G3). */
public class ColumnarDbException extends RuntimeException {
    public ColumnarDbException(String message) {
        super(message);
    }

    public ColumnarDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
