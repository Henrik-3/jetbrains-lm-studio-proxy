package com.hdev.ollamaproxy.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.openapi.diagnostic.Logger;
import io.javalin.http.Context;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public class OllamaWebUIClient implements ProviderClient {
    private static final Logger LOG = Logger.getInstance(ProxyServer.class);
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaWebUIClient(String apiKey, String baseUrl) {
        // Ensure the base URL always ends with a slash for consistent path concatenation
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5)) // Also good to set a write timeout
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder builder = original.newBuilder()
                            // Use "Authorization" header, which is standard. OpenWebUI uses this.
                            .header("Authorization", "Bearer " + apiKey);
                    return chain.proceed(builder.build());
                })
                .build();
    }

    @Override
    public String getModels() throws Exception {
        // This method was already correct.
        Request request = new Request.Builder().url(baseUrl + "api/models").get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get models: " + response);
            }
            return Objects.requireNonNull(response.body()).string();
        }
    }

    @Override
    public String chat(ObjectNode request) throws Exception {
        // ✅ Do NOT force streaming
        request.put("stream", false);

        RequestBody body = RequestBody.create(request.toString(), MediaType.get("application/json"));
        Request apiRequest = new Request.Builder().url(baseUrl + "api/chat/completions").post(body).build();

        try (Response response = httpClient.newCall(apiRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Upstream server error: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            JsonNode json = mapper.readTree(responseBody);

            // ✅ If upstream returns a chat/completion format, wrap it into Ollama-style
            ObjectNode message = mapper.createObjectNode();
            message.put("role", "assistant");

            // Check if it's already a "message" object or a "choices" array
            if (json.has("choices")) {
                String content = json.get("choices").get(0).get("message").get("content").asText();
                message.put("content", content);
            } else if (json.has("message")) {
                message.set("content", json.get("message").get("content"));
            } else {
                message.put("content", json.path("content").asText());
            }

            ObjectNode result = mapper.createObjectNode();
            result.set("message", message);
            result.put("done", true);
            result.put("model", json.path("model").asText());
            result.put("total_duration", 0);
            result.put("eval_count", 0);
            result.put("prompt_eval_count", 0);
            return result.toString();
        }
    }

    @Override
    public void chatStream(ObjectNode request, Context ctx, StreamHandler handler) throws Exception {
        // CRITICAL FIX: Explicitly set stream to true for the upstream request.
        request.put("stream", true);
        request.remove("keep_alive");
        request.remove("options");

        RequestBody body = RequestBody.create(request.toString(), MediaType.get("application/json"));
        LOG.info("Request: " + request.toString());
        Request apiRequest = new Request.Builder()
                .url(baseUrl + "api/chat/completions")
                .header("Accept", "application/x-ndjson")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(apiRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                // Read the body for a better error message if possible
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Upstream server error: " + response.code() + " " + response.message() + " - " + errorBody);
            }

            // The rest of your logic for reading the stream was correct.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;

                    if (line.startsWith("data:")) {
                        String jsonStr = line.substring(6).trim();
                        if ("[DONE]".equals(jsonStr)) break;

                        LOG.info("Response: " + jsonStr);

                        try {
                            handler.handle(jsonStr);
                        } catch (Exception e) {
                            LOG.error("Error handling stream chunk: " + jsonStr);
                            e.printStackTrace();
                            throw e;
                        }
                    }
                }
            }
        }
    }
}