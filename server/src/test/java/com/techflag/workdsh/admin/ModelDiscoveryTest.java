package com.techflag.workdsh.admin;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:sqlite:file:model-discovery-test?mode=memory&cache=shared",
 "workdsh.bootstrap.admin-email=discovery@example.test",
 "workdsh.bootstrap.admin-password=DiscoveryPassword123!",
 "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class ModelDiscoveryTest {
 @Autowired ModelProviders providers;@Autowired ModelProvidersController controller;@Autowired AuthService auth;
 private static final String KEY="internal-member-key-abcdefghijklmnopqrstuvwxyz";
 private ModelProviders.Provider provider(String baseUrl,String protocol,String key){
  return new ModelProviders.Provider("company","Company",baseUrl,protocol,true,
   List.of(new ModelProviders.Model("qwen-plus-256K","Saved Qwen",64000L,16000L,false),
    new ModelProviders.Model("qwen38-efi","Saved Qwen38",131072L,8192L,true)),false,key);
 }
 @Test void discoversUpstreamModelsWithSavedAndTypedKeys() throws Exception {
  var authorization=new AtomicReference<String>();
  var typedKey=new AtomicReference<String>();
  var version=new AtomicReference<String>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/models",exchange->{
   authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
   exchange.getRequestBody().readAllBytes();
   byte[] result=("{\"object\":\"list\",\"data\":["
    +"{\"id\":\"qwen-plus-256K\",\"object\":\"model\",\"name\":\"Qwen Plus\",\"meta\":{\"allow_image\":true,\"vision_width\":0,\"vision_height\":0,\"reasoning\":false}},"
    +"{\"id\":\"deepseek-chat\",\"object\":\"model\",\"meta\":{\"allow_image\":false}},"
     +"{\"id\":\"plain-model\",\"object\":\"model\"},"
     +"{\"id\":\"qwen-embed\",\"object\":\"model\"},"
     +"{\"id\":\"qwen38-efi\",\"object\":\"model\",\"max_model_len\":131072,\"root\":\"/models/qwen38-mtp\"},"
     +"{\"id\":\"qwen-vl\",\"object\":\"model\",\"task\":\"ocr\",\"backend\":\"chat-vision\"},"
     +"{\"id\":\"\",\"object\":\"model\"},"
     +"{\"id\":\"bad id\",\"object\":\"model\"},"
     +"{\"id\":\"qwen-plus-256K\",\"object\":\"model\"},"
     +"{\"id\":\"weird\",\"object\":\"model\",\"context_window\":999999999999,\"max_output_tokens\":-5}"
    +"]}").getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");
   exchange.sendResponseHeaders(200,result.length);
   try(var out=exchange.getResponseBody()){out.write(result);}
  });
  server.createContext("/anthropic/v1/models",exchange->{
   typedKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
   version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
   exchange.getRequestBody().readAllBytes();
   byte[] result=("{\"models\":["
    +"{\"id\":\"claude-3-5-sonnet\",\"display_name\":\"Claude 3.5 Sonnet\",\"meta\":{\"supports_vision\":true}},"
    +"{\"id\":\"claude-haiku\",\"meta\":{\"allow_image\":false,\"input_modalities\":[\"text\",\"image\"]}}"
    +"]}").getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");
   exchange.sendResponseHeaders(200,result.length);
   try(var out=exchange.getResponseBody()){out.write(result);}
  });
  server.start();
  try {
   String bearer="Bearer "+auth.login("discovery@example.test","DiscoveryPassword123!").token();var actor=auth.actor(bearer);String baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
   controller.update(bearer,new ModelProviders.Update(0,List.of(provider(baseUrl,"openai-completions","saved-supplier-secret")),KEY));
   var saved=controller.discover(bearer,new ModelProviders.Discover("company","","openai-completions",""));
   assertEquals(200,saved.getStatusCode().value());assertEquals("no-store",saved.getHeaders().getFirst("Cache-Control"));
   var models=saved.getBody().models();
    assertEquals(7,models.size());assertEquals(7,models.stream().map(ModelProviders.DiscoveredModel::id).distinct().count());
   var qwen=models.stream().filter(m->m.id().equals("qwen-plus-256K")).findFirst().orElseThrow();
   assertEquals("Qwen Plus",qwen.displayName());assertEquals(256000L,qwen.contextWindow());assertEquals(8192L,qwen.maxOutputTokens());assertTrue(qwen.images());
   var deepseek=models.stream().filter(m->m.id().equals("deepseek-chat")).findFirst().orElseThrow();
   assertEquals(128000L,deepseek.contextWindow());assertEquals(8192L,deepseek.maxOutputTokens());assertFalse(deepseek.images());
    var plain=models.stream().filter(m->m.id().equals("plain-model")).findFirst().orElseThrow();
    assertNull(plain.contextWindow());assertNull(plain.maxOutputTokens());assertFalse(plain.images());
    var embed=models.stream().filter(m->m.id().equals("qwen-embed")).findFirst().orElseThrow();
    assertNull(embed.contextWindow());assertNull(embed.maxOutputTokens());assertFalse(embed.images());
    var qwen38=models.stream().filter(m->m.id().equals("qwen38-efi")).findFirst().orElseThrow();
    assertEquals(131072L,qwen38.contextWindow());assertEquals(8192L,qwen38.maxOutputTokens());assertTrue(qwen38.images());
    var qwenVl=models.stream().filter(m->m.id().equals("qwen-vl")).findFirst().orElseThrow();
    assertEquals(131072L,qwenVl.contextWindow());assertEquals(8192L,qwenVl.maxOutputTokens());assertTrue(qwenVl.images());
   var weird=models.stream().filter(m->m.id().equals("weird")).findFirst().orElseThrow();
   assertNull(weird.contextWindow());assertNull(weird.maxOutputTokens());
   assertEquals("Bearer saved-supplier-secret",authorization.get());
   assertFalse(saved.getBody().toString().contains("saved-supplier-secret"));
   var stored=providers.read(actor).providers().get(0).models().get(0);
   assertEquals(64000L,stored.contextWindow());assertEquals(16000L,stored.maxOutputTokens());assertFalse(stored.images());

   var typed=controller.discover(bearer,new ModelProviders.Discover("","http://127.0.0.1:"+server.getAddress().getPort()+"/anthropic","anthropic-messages","typed-supplier-key"));
   assertEquals(2,typed.getBody().models().size());
   var claude=typed.getBody().models().get(0);
   assertEquals("claude-3-5-sonnet",claude.id());assertEquals(200000L,claude.contextWindow());assertEquals(8192L,claude.maxOutputTokens());assertTrue(claude.images());
   var haiku=typed.getBody().models().get(1);
   assertTrue(haiku.images());assertEquals(200000L,haiku.contextWindow());
   assertEquals("typed-supplier-key",typedKey.get());assertEquals("2023-06-01",version.get());
   assertFalse(typed.getBody().toString().contains("typed-supplier-key"));

   assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.discover(bearer,new ModelProviders.Discover("company","http://127.0.0.1:"+server.getAddress().getPort()+"/other","openai-completions",""))).getStatusCode().value());
   assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.discover(bearer,new ModelProviders.Discover("","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","openai-completions",""))).getStatusCode().value());
   assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.discover(bearer,new ModelProviders.Discover("","","openai-completions","typed-supplier-key"))).getStatusCode().value());
  } finally {server.stop(0);}
 }
}
