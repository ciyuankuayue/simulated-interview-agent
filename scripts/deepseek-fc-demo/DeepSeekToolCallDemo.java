import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * 最小验证：DeepSeek 的 function calling 能不能返回 tool_calls。
 * 零依赖，用裸 HttpClient 直接打 DeepSeek 原生接口，只为验「通道」通不通。
 *
 * 运行（JDK 11+，本例 JDK 24 直接跑 .java 源码即可）：
 *   Windows:  set DEEPSEEK_API_KEY=sk-xxxx
 *   Linux/Mac: export DEEPSEEK_API_KEY=sk-xxxx
 *   java DeepSeekToolCallDemo.java
 *
 * 注意：
 *  - 用 deepseek-chat（V3，非思考型）验证，reasoner（R1）不支持 tool call。
 *  - model 串以 DeepSeek 平台当前文档为准，旧 chat/reasoner 别名可能已退役。
 */
public class DeepSeekToolCallDemo {

    public static void main(String[] args) throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("✗ 没找到 DEEPSEEK_API_KEY 环境变量，先 export/set 再跑");
            return;
        }

        // 请求体：问天气 + 声明一个 get_weather 工具，让模型自行决定是否调用
        String body = """
        {
          "model": "deepseek-chat",
          "messages": [
            {"role": "user", "content": "北京今天天气怎么样？"}
          ],
          "tools": [
            {
              "type": "function",
              "function": {
                "name": "get_weather",
                "description": "查询指定城市的当前天气",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "location": {"type": "string", "description": "城市名，如 北京"},
                    "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]}
                  },
                  "required": ["location"]
                }
              }
            }
          ],
          "tool_choice": "auto"
        }
        """;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/chat/completions"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String out = resp.body();

        System.out.println("HTTP " + resp.statusCode());
        System.out.println("=== 原始响应 ===");
        System.out.println(out);

        if (out.contains("\"tool_calls\"")) {
            System.out.println("\n✓ function calling 验通：响应里出现了 tool_calls（含 name + arguments）");
        } else {
            System.out.println("\n✗ 没看到 tool_calls，看上面是不是报错，或模型串不对");
        }
    }
}
