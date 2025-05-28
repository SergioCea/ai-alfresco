package es.sergio.alfresco.evaluators;

import org.alfresco.web.evaluator.BaseEvaluator;
import org.json.simple.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class MimeTypeEvaluator extends BaseEvaluator {
    private static final Set<String> ALLOWED_MIMETYPES_TRANSLATE = new HashSet<>();

    static {
        ALLOWED_MIMETYPES_TRANSLATE.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ALLOWED_MIMETYPES_TRANSLATE.add("application/pdf");
        ALLOWED_MIMETYPES_TRANSLATE.add("text/plain");
    }

    @Override
    public boolean evaluate(JSONObject jsonObject) {
        try {
            String mimeType = getNodeMimetype(jsonObject);
            return ALLOWED_MIMETYPES_TRANSLATE.contains(mimeType);
        } catch (Exception err) {
            throw new RuntimeException("JSONException whilst running action evaluator: " + err.getMessage());
        }
    }
}
