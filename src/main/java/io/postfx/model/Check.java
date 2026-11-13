package io.postfx.model;

/** Declarative (no-code) assertion evaluated after the response is received. */
public class Check {
    public boolean enabled = true;
    public CheckSource source = CheckSource.STATUS;
    /** Header name or JSON path, depending on the source. */
    public String property = "";
    public CheckOperator operator = CheckOperator.EQUALS;
    /** Expected value, may contain {{variables}}. For TYPE_IS: string|number|boolean|object|array|null */
    public String expected = "";

    public Check() {}

    public Check(CheckSource source, String property, CheckOperator operator, String expected) {
        this.source = source;
        this.property = property;
        this.operator = operator;
        this.expected = expected;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(source.label);
        if (source.needsProperty() && property != null && !property.isBlank()) {
            sb.append(" ").append(property);
        }
        sb.append(" ").append(operator.label);
        if (operator.needsExpected()) {
            sb.append(" ").append(expected);
        }
        return sb.toString();
    }
}
