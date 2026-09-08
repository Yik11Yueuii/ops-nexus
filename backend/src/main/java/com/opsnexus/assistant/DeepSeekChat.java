package com.opsnexus.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekChat {
    public record ConversationMessage(String role,String content){}
    private final String key, url, model;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public DeepSeekChat(@Value("${DEEPSEEK_API_KEY:}") String key,
            @Value("${ops.chat-url}") String url,@Value("${ops.chat-model}") String model,ObjectMapper json){
        this.key=key;this.url=url;this.model=model;this.json=json;
    }
    public boolean configured(){return !key.isBlank();}
    public String modelName(){return model;}
    public String complete(String system,String question){
        if(!configured())throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法生成 SQL");
        try{
            var body=Map.of("model",model,"stream",false,"temperature",0,"max_tokens",500,
                "messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",question)));
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("DeepSeek 请求失败（HTTP "+response.statusCode()+"）");
            return json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("SQL 生成已取消");}
        catch(IllegalStateException e){throw e;}catch(Exception e){throw new IllegalStateException("无法连接 DeepSeek 生成 SQL");}
    }
    public void stream(String system,String question,Consumer<String> output){
        stream(system,List.of(),question,output);
    }
    public void stream(String system,List<ConversationMessage> history,String question,Consumer<String> output){
        if(!configured())throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法生成回答");
        try{
            var messages=new ArrayList<Map<String,String>>();messages.add(Map.of("role","system","content",system));
            for(var item:history)if(("user".equals(item.role())||"assistant".equals(item.role()))&&!item.content().isBlank())messages.add(Map.of("role",item.role(),"content",item.content()));
            messages.add(Map.of("role","user","content",question));
            var body=Map.of("model",model,"stream",true,"temperature",0.2,"max_tokens",800,"messages",messages);
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofLines());
            if(response.statusCode()!=200)throw new IllegalStateException("DeepSeek 请求失败（HTTP "+response.statusCode()+"），请检查密钥、余额和模型配置");
            response.body().forEach(line->{
                if(!line.startsWith("data:"))return;
                String data=line.substring(5).strip();
                if(data.equals("[DONE]")||data.isBlank())return;
                try{
                    var content=json.readTree(data).path("choices").path(0).path("delta").path("content");
                    if(content.isTextual()&&!content.asText().isEmpty())output.accept(content.asText());
                }catch(Exception e){throw new StreamFailure("DeepSeek 流式响应格式异常");}
            });
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("回答生成已取消");}
        catch(StreamFailure|IllegalStateException e){throw e;}
        catch(Exception e){throw new IllegalStateException("无法连接 DeepSeek，请检查网络和接口地址");}
    }
    private static class StreamFailure extends RuntimeException{StreamFailure(String m){super(m);}}
}
