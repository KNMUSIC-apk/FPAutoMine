package dev.fpaddon.automine;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gọi Messages API của Claude để lấy quyết định cho bot. Chỉ gửi số liệu của bot (đói, máu, ban đêm...),
 * không gửi tên người chơi hay nội dung chat. Khoá API không bao giờ xuất hiện trong log hay thông báo lỗi.
 */
final class ClaudeClient {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String API_VERSION = "2023-06-01";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_TOKENS = 60;

    private static final String SYSTEM_PROMPT = """
            You control one Minecraft survival bot. Reply with ONLY one line of JSON and nothing else.
            Allowed replies:
            {"task":"gather","block":"<BLOCK>"}
            {"task":"rest"}
            For gather, BLOCK is an uppercase Minecraft block id and must be one of: any *_LOG, any *_ORE, \
            STONE, DEEPSLATE, COBBLESTONE, DIRT, GRAVEL, SAND, CLAY, NETHERRACK.
            Choose rest when there is no food and the bot is hungry, or when hostiles are nearby.
            Otherwise choose gather with the block most useful for survival: wood first, then stone and ore.
            """;

    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final HttpClient http;
    private final String apiKey;
    private final String model;

    /** @param apiKey khoá API, có thể null hoặc rỗng; khi đó {@link #ready()} trả về false. */
    ClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        // Dùng cấu hình proxy của JVM (nếu có), giống các kết nối mạng khác của server.
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        this.http = builder.build();
    }

    boolean ready() {
        return !apiKey.isEmpty();
    }

    /**
     * Hỏi Claude việc nên làm với trạng thái {@code state} (chuỗi số liệu ngắn). Kết quả null nếu câu trả lời
     * không đúng định dạng hoặc chọn việc ngoài danh sách cho phép. Lỗi mạng hoặc HTTP được báo qua future,
     * thông báo lỗi chỉ chứa mã trạng thái, không có khoá hay nội dung trả lời.
     */
    CompletableFuture<DecisionParser.Decision> decide(String state) {
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(state), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Claude trả về HTTP " + response.statusCode());
                    }
                    return DecisionParser.parse(firstText(response.body()));
                });
    }

    private String requestBody(String state) {
        return "{\"model\":" + jsonString(model)
                + ",\"max_tokens\":" + MAX_TOKENS
                + ",\"system\":" + jsonString(SYSTEM_PROMPT)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString("Bot state: " + state) + "}]}";
    }

    /** Lấy trường "text" đầu tiên trong phản hồi và giải mã chuỗi JSON của nó. */
    static String firstText(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = TEXT_FIELD.matcher(body);
        return m.find() ? jsonUnescape(m.group(1)) : null;
    }

    /** Bọc chuỗi thành literal JSON, escape đủ ký tự điều khiển. */
    static String jsonString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Giải mã nội dung chuỗi JSON (không có dấu nháy bao ngoài). */
    static String jsonUnescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append('?');
                        }
                    }
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }
}
