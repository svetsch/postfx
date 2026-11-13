package io.postfx.model;

public enum CheckOperator {
    EQUALS("equals"), NOT_EQUALS("not equals"), CONTAINS("contains"), NOT_CONTAINS("does not contain"),
    GREATER_THAN(">"), GREATER_OR_EQUAL(">="), LESS_THAN("<"), LESS_OR_EQUAL("<="),
    EXISTS("exists"), NOT_EXISTS("does not exist"), MATCHES("matches regex"),
    IS_EMPTY("is empty"), IS_NOT_EMPTY("is not empty"), TYPE_IS("type is");

    public final String label;

    CheckOperator(String label) {
        this.label = label;
    }

    public boolean needsExpected() {
        return switch (this) {
            case EXISTS, NOT_EXISTS, IS_EMPTY, IS_NOT_EMPTY -> false;
            default -> true;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
