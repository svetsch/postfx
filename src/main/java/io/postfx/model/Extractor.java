package io.postfx.model;

/** Declarative extraction of a response value into a variable, used to chain requests. */
public class Extractor {
    public boolean enabled = true;
    public String variable = "";
    public ExtractSource source = ExtractSource.JSON_PATH;
    public String expression = "";
    public VarScope scope = VarScope.ENVIRONMENT;

    public Extractor() {}

    public Extractor(String variable, ExtractSource source, String expression, VarScope scope) {
        this.variable = variable;
        this.source = source;
        this.expression = expression;
        this.scope = scope;
    }
}
