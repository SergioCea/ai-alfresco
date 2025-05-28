package es.sergio.alfresco.evaluators;

import org.alfresco.web.evaluator.BaseEvaluator;
import org.json.simple.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class MimeTypeEvaluatorExtractMetadata extends BaseEvaluator {
    private static final Set<String> ALLOWED_MIMETYPES_METADATA = new HashSet<>();

    static {
        ALLOWED_MIMETYPES_METADATA.add("image/png");
        ALLOWED_MIMETYPES_METADATA.add("image/jpeg");
        ALLOWED_MIMETYPES_METADATA.add("image/tiff");
        ALLOWED_MIMETYPES_METADATA.add("application/pdf");
    }

    @Override
    public boolean evaluate(JSONObject jsonObject) {
        try {
            String mimeType = getNodeMimetype(jsonObject);
            return ALLOWED_MIMETYPES_METADATA.contains(mimeType);
        } catch (Exception err) {
            throw new RuntimeException("JSONException whilst running action evaluator: " + err.getMessage());
        }
    }
}
