package com.eluno.pos.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.net.URI;

@Component
public class ApiClient {
  private final RestTemplate rest = new RestTemplate();
  private final ObjectMapper json;
  private final String base;

  public ApiClient(ObjectMapper json, @Value("${eluno.mock-url}") String base) {
    this.json = json;
    this.base = base.replaceAll("/$", "");
  }

  public JsonNode getShopify(String path) {
    return request(HttpMethod.GET, base + "/admin/api/2026-10" + path, null);
  }

  public JsonNode postShopify(String path, JsonNode body) {
    return request(HttpMethod.POST, base + "/admin/api/2026-10" + path, body);
  }

  public JsonNode getSmt(String path) {
    return request(HttpMethod.GET, base + path, null);
  }

  public JsonNode postTerminal(String path, JsonNode body) {
    return request(HttpMethod.POST, base + path, body);
  }

  private JsonNode request(HttpMethod method, String url, JsonNode body) {
    for (int attempt = 0; attempt < 4; attempt++)
      try {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        byte[] payload = body == null ? null : json.writeValueAsBytes(body);
        if (payload != null)
          h.setContentLength(payload.length);
        return rest.exchange(URI.create(url), method, new HttpEntity<>(payload, h), JsonNode.class).getBody();
      } catch (HttpStatusCodeException e) {
        if ((e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503) && attempt < 3) {
          try {
            Thread.sleep(Long.parseLong(e.getResponseHeaders().getFirst("Retry-After")) * 1000L);
          } catch (Exception ignored) {
            try {
              Thread.sleep(200L);
            } catch (InterruptedException x) {
              Thread.currentThread().interrupt();
            }
          }
          continue;
        }
        throw new ApiException(502, "External API error: " + e.getResponseBodyAsString());
      } catch (Exception e) {
        throw new ApiException(503, "External service unavailable: " + e.getMessage());
      }
    throw new ApiException(503, "External retry budget exhausted");
  }

  public ObjectNode object() {
    return json.createObjectNode();
  }

  public ArrayNode array() {
    return json.createArrayNode();
  }

  public static class ApiException extends RuntimeException {
    public final int status;

    public ApiException(int s, String m) {
      super(m);
      status = s;
    }
  }
}
