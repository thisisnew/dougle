package dev.dougle.search.adapter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import java.util.Locale;

final class SearchRepresentation {
    private SearchRepresentation() {}

    static String evidence(String path, String text) {
        return path.toLowerCase(Locale.ROOT).endsWith(".java") ? of(path, text) : text;
    }

    static String of(String path, String text) {
        String original = path + "\n" + text;
        if (!path.toLowerCase(Locale.ROOT).endsWith(".java")) return original;
        String names = original.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1 $2").replaceAll("[_/]+", " ");
        var facts = new StringBuilder();
        var parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(text);
        if (!parsed.isSuccessful()) return names + "\n" + original;
        parsed.getResult().ifPresent(unit -> {
            for (var constructor : unit.findAll(ConstructorDeclaration.class)) {
                for (var parameter : constructor.getParameters()) {
                    for (var assignment : constructor.findAll(AssignExpr.class)) {
                        if (facts.length() >= 2048 || assignment.getParentNode().flatMap(node -> node.getParentNode())
                                .filter(node -> node == constructor.getBody()).isEmpty()
                                || !assignment.getValue().isNameExpr()
                                || !assignment.getValue().asNameExpr().getNameAsString().equals(parameter.getNameAsString())
                                || !assignment.getTarget().isFieldAccessExpr()
                                || !assignment.getTarget().asFieldAccessExpr().getScope().isThisExpr()) continue;
                        facts.append("Constructor injection: ").append(constructor.getNameAsString())
                                .append(" receives ").append(parameter.getTypeAsString())
                                .append(" from the caller and assigns it to ").append(assignment.getTarget())
                                .append(". 생성자에서 외부 매개변수를 전달받아 필드에 저장합니다.\n");
                    }
                }
            }
        });
        return facts + names + "\n" + original;
    }
}
