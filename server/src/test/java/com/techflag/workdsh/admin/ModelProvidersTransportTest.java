package com.techflag.workdsh.admin;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:sqlite:file:providers-transport-test?mode=memory&cache=shared",
 "workdsh.bootstrap.admin-email=transport@example.test",
 "workdsh.bootstrap.admin-password=TransportPassword123!",
 "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class ModelProvidersTransportTest {
 @Autowired ModelProviders providers;@Autowired ModelProvidersController controller;@Autowired AuthService auth;
 private static final String KEY="internal-member-key-abcdefghijklmnopqrstuvwxyz";
 private ModelProviders.Provider provider(String baseUrl){
  return new ModelProviders.Provider("local","Local",baseUrl,"openai-completions",true,
   List.of(new ModelProviders.Model("local-model","Local Model",262144L,null,false)),false,"local-no-key");
 }
 @Test void httpOnlyForPrivateOrLoopbackHosts(){
  String bearer="Bearer "+auth.login("transport@example.test","TransportPassword123!").token();var actor=auth.actor(bearer);
  assertEquals(1,controller.update(bearer,new ModelProviders.Update(0,List.of(provider("http://127.0.0.1:17701/v1")),KEY)).getBody().revision());
  assertEquals(2,controller.update(bearer,new ModelProviders.Update(1,List.of(provider("http://100.95.232.103:17701/v1")),KEY)).getBody().revision());
  assertEquals(3,controller.update(bearer,new ModelProviders.Update(2,List.of(provider("http://172.16.0.1:17701/v1")),KEY)).getBody().revision());
  long rev=providers.read(actor).revision();
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(rev,List.of(provider("http://example.com/v1")),KEY))).getStatusCode().value());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(rev,List.of(provider("http://8.8.8.8/v1")),KEY))).getStatusCode().value());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(rev,List.of(provider("http://172.32.0.1/v1")),KEY))).getStatusCode().value());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(rev,List.of(provider("http://192.168.1.1.evil.test/v1")),KEY))).getStatusCode().value());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(rev,List.of(provider("ftp://127.0.0.1/v1")),KEY))).getStatusCode().value());
  assertEquals("http://172.16.0.1:17701/v1",providers.read(actor).providers().get(0).baseUrl());
 }
}
