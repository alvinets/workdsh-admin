package com.techflag.workdsh.admin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelProviders {
 private static final HttpClient DISCOVERY_CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 private static final int DISCOVERY_LIMIT=1024*1024;
 private static final Pattern SIZE_TOKEN=Pattern.compile("(?i)(?:^|[^a-z0-9])(\\d{1,4})\\s*([km])(?![a-z0-9])");
 private static final Pattern NON_CHAT=Pattern.compile("(?i)(embed|embedding|rerank|ocr|asr|tts|audio|voice|whisper|sensevoice|kokoro|cosyvoice|moderation)");
 private static final Pattern VISION_ID=Pattern.compile("(?i)(?:^|[-_/])(?:vl|vision|omni|multimodal|llava|pixtral)(?:$|[-_/])");
 private final JdbcTemplate db; private final AuthService auth; private final MemberSecrets secrets; private final DatabaseDialect dialect; private final ObjectMapper json; private final MemberController audit;
 public ModelProviders(JdbcTemplate db,AuthService auth,MemberSecrets secrets,DatabaseDialect dialect,ObjectMapper json,MemberController audit){this.db=db;this.auth=auth;this.secrets=secrets;this.dialect=dialect;this.json=json;this.audit=audit;}
 public record Model(String id,String displayName,Long contextWindow,Long maxOutputTokens,boolean images){}
 public record Provider(String id,String displayName,String baseUrl,String protocol,boolean enabled,List<Model> models,boolean hasKey,String apiKey){}
 public record View(long revision,List<Provider> providers){}
 public record Update(long expectedRevision,List<Provider> providers,String accessKey){}
 public record Resolved(String baseUrl,String apiKey){}
 public record Discover(String providerId,String baseUrl,String protocol,String apiKey){}
 public record DiscoveredModel(String id,String displayName,Long contextWindow,Long maxOutputTokens,boolean images){}
 public record Discovery(List<DiscoveredModel> models){}
 private record Stored(long revision,List<Provider> providers){}
 private Stored stored(String org){
  var rows=db.query("select revision,providers_json from organization_model_providers where organization_id=?",(r,n)->{try{return new Stored(r.getLong(1),json.readValue(r.getString(2),new TypeReference<List<Provider>>(){}));}catch(Exception e){throw new IllegalStateException("Invalid provider configuration");}},org);
  if(!rows.isEmpty())return rows.get(0);
  return new Stored(0,List.of());
 }
 public View read(AuthService.Actor actor){auth.requireReady(actor);auth.requireAdmin(actor);var s=stored(actor.organizationId());return new View(s.revision,s.providers.stream().map(p->new Provider(p.id,p.displayName,p.baseUrl,p.protocol,p.enabled,p.models,p.hasKey,null)).toList());}
 public String reveal(AuthService.Actor actor,String id){auth.requireReady(actor);auth.requireAdmin(actor);var provider=stored(actor.organizationId()).providers.stream().filter(p->p.id.equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!provider.hasKey)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"API key not configured");String value=secrets.cryptOrganizationModel(false,actor.organizationId(),provider.apiKey);audit.audit(actor,"organization.model.provider.key.viewed",id);return value;}
 @Transactional public View update(AuthService.Actor actor,Update update){
  auth.requireReady(actor);auth.requireAdmin(actor);
  if(update==null||update.expectedRevision<0||update.providers==null||update.providers.size()>32)bad("Invalid providers");
  String org=actor.organizationId();db.queryForObject(dialect.lock("select id from organizations where id=?"),String.class,org);
  var current=stored(org);if(current.revision!=update.expectedRevision)throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuration changed; refresh before saving");
  Set<String> providerIds=new HashSet<>(),modelIds=new HashSet<>();List<Provider> next=new ArrayList<>();
  for(var p:update.providers){
   if(p==null||p.id==null||!p.id.matches("[a-z][a-z0-9_-]{0,63}")||!providerIds.add(p.id)||p.displayName==null||p.displayName.isBlank()||p.displayName.length()>128||!List.of("openai-completions","anthropic-messages").contains(p.protocol)||p.models==null||p.models.size()>64)bad("Invalid provider fields");
   try{URI u=URI.create(p.baseUrl);if(!secureTransport(u)||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||p.baseUrl.length()>1024)bad("HTTPS provider URL required (HTTP is allowed only for private or loopback hosts)");}catch(Exception e){bad("HTTPS provider URL required (HTTP is allowed only for private or loopback hosts)");}
   Set<String> own=new HashSet<>();for(var m:p.models){if(m==null||m.id==null||!m.id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")||!own.add(m.id)||m.displayName==null||m.displayName.length()>128||!validLimit(m.contextWindow)||!validLimit(m.maxOutputTokens)||m.contextWindow!=null&&m.maxOutputTokens!=null&&m.maxOutputTokens>m.contextWindow)bad("Invalid model fields");if(p.enabled&&!modelIds.add(m.id))bad("Enabled interfaces must use different model IDs");}
   String encrypted=current.providers.stream().filter(old->old.id.equals(p.id)).map(Provider::apiKey).findFirst().orElse("");
   if(p.apiKey!=null&&!p.apiKey.isEmpty()){if(p.apiKey.length()>8192||p.apiKey.chars().anyMatch(Character::isISOControl))bad("Invalid API key");encrypted=secrets.cryptOrganizationModel(true,org,p.apiKey);}
   if(p.enabled&&(encrypted.isEmpty()||p.models.isEmpty()))bad("Enabled interface requires a key and at least one model");
   next.add(new Provider(p.id,p.displayName,p.baseUrl,p.protocol,p.enabled,List.copyOf(p.models),!encrypted.isEmpty(),encrypted));
  }
  try{String payload=json.writeValueAsString(next);if(current.revision==0)db.update("insert into organization_model_providers(organization_id,revision,providers_json) values(?,1,?)",org,payload);else if(db.update("update organization_model_providers set revision=?,providers_json=? where organization_id=? and revision=?",current.revision+1,payload,org,current.revision)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT);}catch(ResponseStatusException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot save providers",e);}
  audit.audit(actor,"organization.model.providers.updated",org);return read(actor);
 }
  Discovery discover(AuthService.Actor actor,Discover request){
   auth.requireReady(actor);auth.requireAdmin(actor);
   if(request==null)bad("Discovery request required");
   String org=actor.organizationId();
   Provider saved=null;
   if(request.providerId()!=null&&!request.providerId().isBlank())saved=stored(org).providers.stream().filter(p->p.id.equals(request.providerId().trim())).findFirst().orElse(null);
   String requestedBase=request.baseUrl()==null||request.baseUrl().isBlank()?null:request.baseUrl().trim();
   String baseUrl=requestedBase!=null?requestedBase:saved==null?null:saved.baseUrl();
   if(baseUrl==null||baseUrl.isBlank())bad("Provider URL required");
   String protocol=request.protocol()==null||request.protocol().isBlank()?saved==null?"openai-completions":saved.protocol():request.protocol().trim();
   if(!List.of("openai-completions","anthropic-messages").contains(protocol))bad("Unsupported provider protocol");
   String typedKey=request.apiKey()==null||request.apiKey().isBlank()?null:request.apiKey();
   String apiKey;
   if(typedKey!=null)apiKey=typedKey;
   else{
    if(saved==null)bad("API key required");
    if(!normalizedUrl(baseUrl).equals(normalizedUrl(saved.baseUrl())))bad("API key required when changing the provider URL");
    if(!saved.hasKey()||saved.apiKey()==null||saved.apiKey().isEmpty())bad("API key required");
    apiKey=secrets.cryptOrganizationModel(false,org,saved.apiKey());
   }
   Map<String,Model> savedModels=saved==null?Map.of():saved.models().stream().collect(java.util.stream.Collectors.toMap(Model::id,m->m,(a,b)->a));
   URI target=discoveryUri(validBaseUrl(baseUrl));
   var builder=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
   if(protocol.equals("anthropic-messages"))builder.header("x-api-key",apiKey).header("anthropic-version","2023-06-01");
   else builder.header("Authorization","Bearer "+apiKey);
   HttpResponse<InputStream> reply;
   try{reply=DISCOVERY_CLIENT.send(builder.GET().build(),HttpResponse.BodyHandlers.ofInputStream());}
   catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider connection failed");}
   catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider connection failed");}
   try(InputStream body=reply.body()){
     if(reply.statusCode()<200||reply.statusCode()>=300)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider rejected request");
     return new Discovery(parseDiscovered(body,savedModels));
   }catch(ResponseStatusException e){throw e;}
   catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid model provider response");}
  }
  private static String normalizedUrl(String value){String v=value==null?"":value.trim();while(v.endsWith("/"))v=v.substring(0,v.length()-1);return v;}
  private static URI validBaseUrl(String value){
   URI u;
   try{u=URI.create(value);}catch(Exception e){bad("HTTPS provider URL required (HTTP is allowed only for private or loopback hosts)");return null;}
   if(!secureTransport(u)||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||value.length()>1024)bad("HTTPS provider URL required (HTTP is allowed only for private or loopback hosts)");
   return u;
  }
  private static URI discoveryUri(URI base){
   String path=base.getPath()==null?"":base.getPath();
   while(path.endsWith("/"))path=path.substring(0,path.length()-1);
   if(path.endsWith("/models")){}
   else if(path.endsWith("/v1"))path+="/models";
   else path+="/v1/models";
   try{return new URI(base.getScheme(),null,base.getHost(),base.getPort(),path,null,null);}catch(Exception e){bad("Invalid provider URL");return null;}
  }
  private List<DiscoveredModel> parseDiscovered(InputStream input,Map<String,Model> savedModels) throws java.io.IOException{
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int total=0,n;
   while((n=input.read(buffer))!=-1){total+=n;if(total>DISCOVERY_LIMIT)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider response is too large");bytes.write(buffer,0,n);}
   JsonNode root;
   try{root=json.readTree(bytes.toByteArray());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid model provider response");}
   if(root==null||!root.isObject())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid model provider response");
   JsonNode data=root.path("data");if(!data.isArray())data=root.path("models");
   if(!data.isArray())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid model provider response");
   Map<String,DiscoveredModel> unique=new LinkedHashMap<>();
   for(JsonNode node:data){
    if(node==null||!node.isObject())continue;
    String id=textValue(node.get("id"));if(id!=null)id=id.trim();
    if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")||unique.containsKey(id))continue;
    String display=firstText(node,"display_name","displayName","name");
    if(display!=null&&(display.isBlank()||display.length()>128))display=null;
    Long context=firstLimit(node,"context_window","context_length","max_context_length","max_model_len","max_input_tokens");
    Long output=firstLimit(node,"max_output_tokens","max_completion_tokens","max_tokens");
    Boolean image=imageSignal(node);
    if(image==null&&VISION_ID.matcher(id).find())image=true;
    Metadata metadata=inferredMetadata(id);
    if(context==null)context=metadata.context();else if(!validLimit(context))context=null;
    if(output==null)output=metadata.output();else if(!validLimit(output))output=null;
    Model savedModel=savedModels.get(id);
    if(savedModel!=null){
     if(context==null&&validLimit(savedModel.contextWindow()))context=savedModel.contextWindow();
     if(output==null&&validLimit(savedModel.maxOutputTokens()))output=savedModel.maxOutputTokens();
     if(image==null)image=savedModel.images();
    }
    if(context!=null&&output!=null&&output>context)output=null;
    unique.put(id,new DiscoveredModel(id,display==null?id:display,context,output,image!=null?image:metadata.images()));
   }
   return new ArrayList<>(unique.values());
  }
  private record Metadata(Long context,Long output,boolean images){}
  private static Metadata inferredMetadata(String id){
   String lower=id.toLowerCase(java.util.Locale.ROOT);
   if(NON_CHAT.matcher(lower).find())return new Metadata(null,null,false);
   Metadata base=curated(lower);
   Long context=base==null?null:base.context();Long output=base==null?null:base.output();boolean images=base!=null&&base.images();
   var size=SIZE_TOKEN.matcher(lower);
   if(size.find()){
    long amount=Long.parseLong(size.group(1));long window=size.group(2).equalsIgnoreCase("m")?amount*1000000:amount*1000;
    if(validLimit(window)){context=window;if(output==null)output=defaultOutput(window);}
   }
   if(output==null&&context!=null)output=defaultOutput(context);
   return new Metadata(context,output,images);
  }
  private static Metadata curated(String id){
   if(id.contains("gpt-4.1")||id.contains("gpt-4-1"))return new Metadata(1047576L,32768L,true);
   if(id.contains("gpt-4o"))return new Metadata(128000L,16384L,true);
   if(id.contains("gpt-4"))return new Metadata(128000L,8192L,true);
   if(id.contains("gpt-3.5"))return new Metadata(16385L,4096L,false);
   if(id.contains("gpt"))return new Metadata(128000L,16384L,true);
   if(id.startsWith("o1")||id.startsWith("o3")||id.startsWith("o4"))return new Metadata(200000L,100000L,true);
   if(id.contains("claude"))return new Metadata(200000L,8192L,true);
   if(id.contains("gemini"))return new Metadata(1048576L,8192L,true);
   if(id.contains("deepseek"))return new Metadata(128000L,8192L,false);
   if(id.contains("qwen"))return new Metadata(131072L,8192L,false);
   if(id.contains("llama"))return new Metadata(128000L,4096L,false);
   if(id.contains("mistral"))return new Metadata(128000L,8192L,false);
   if(id.contains("kimi"))return new Metadata(128000L,8192L,false);
   return null;
  }
  private static long defaultOutput(long context){
   if(context>=1000000)return 32768;
   if(context>=200000)return 32768;
   if(context>=128000)return 16384;
   if(context>=64000)return 8192;
   if(context>=32000)return 4096;
   return 2048;
  }
  private static Boolean imageSignal(JsonNode node){
   Boolean explicit=null;
   for(String name:List.of("allow_image","supports_vision","vision")){
    Boolean value=flagValue(node.get(name));if(Boolean.TRUE.equals(value))return true;if(Boolean.FALSE.equals(value))explicit=false;
   }
   JsonNode meta=node.get("meta");
   if(meta!=null&&meta.isObject())for(String name:List.of("allow_image","supports_vision","vision")){
    Boolean value=flagValue(meta.get(name));if(Boolean.TRUE.equals(value))return true;if(Boolean.FALSE.equals(value))explicit=false;
   }
   if(isVisionText(firstText(node,"backend"))||isVisionText(firstText(node,"task")))return true;
   if(containsImage(node)||meta!=null&&meta.isObject()&&containsImage(meta))return true;
   return explicit;
  }
  private static boolean isVisionText(String value){
   if(value==null)return false;
   String normalized=value.trim().toLowerCase(java.util.Locale.ROOT);
   return normalized.contains("vision")||normalized.contains("multimodal")||normalized.equals("vl")||normalized.equals("ocr");
  }
  private static boolean containsImage(JsonNode node){
   for(String name:List.of("input_modalities","inputModalities","capabilities","modalities")){
    JsonNode value=node.get(name);if(value==null||!value.isArray())continue;
    for(JsonNode item:value)if(item.isTextual()){
     String normalized=item.asText().trim().toLowerCase(java.util.Locale.ROOT);
     if(normalized.equals("image")||normalized.equals("vision")||normalized.equals("multimodal"))return true;
    }
   }
   return false;
  }
  private static Long firstLimit(JsonNode node,String... names){
   for(String name:names){Long value=longValue(node.get(name));if(value!=null)return value;}
   JsonNode meta=node.get("meta");if(meta!=null&&meta.isObject())for(String name:names){Long value=longValue(meta.get(name));if(value!=null)return value;}
   return null;
  }
  private static Long longValue(JsonNode node){
   if(node==null)return null;
   if(node.isIntegralNumber())return node.asLong();
   if(node.isTextual()){try{return Long.parseLong(node.asText().trim());}catch(Exception e){return null;}}
   return null;
  }
  private static String firstText(JsonNode node,String... names){
   for(String name:names){String value=textValue(node.get(name));if(value!=null)return value;}
   JsonNode meta=node.get("meta");if(meta!=null&&meta.isObject())for(String name:names){String value=textValue(meta.get(name));if(value!=null)return value;}
   return null;
  }
  private static String textValue(JsonNode node){return node!=null&&node.isTextual()&&!node.asText().isBlank()?node.asText():null;}
  private static Boolean flagValue(JsonNode node){
   if(node==null)return null;
   if(node.isBoolean())return node.asBoolean();
   if(node.isNumber())return node.asInt()!=0;
   if(node.isTextual()){String value=node.asText().trim().toLowerCase(java.util.Locale.ROOT);if(value.equals("true")||value.equals("yes")||value.equals("1"))return true;if(value.equals("false")||value.equals("no")||value.equals("0"))return false;}
   return null;
  }
  private static boolean secureTransport(URI u){
  if("https".equals(u.getScheme()))return true;
  if(!"http".equals(u.getScheme()))return false;
  String host=u.getHost();
  if(host==null)return false;
  if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
  host=host.toLowerCase(java.util.Locale.ROOT);
  if(host.equals("localhost"))return true;
  if(host.contains(":")){
   return host.equals("::1")||host.equals("0:0:0:0:0:0:0:1")||host.startsWith("fc")||host.startsWith("fd")||host.startsWith("fe80:");
  }
  String[] parts=host.split("\\.");
  if(parts.length!=4)return false;
  int[] octets=new int[4];
  for(int i=0;i<4;i++){
   if(!parts[i].matches("\\d{1,3}"))return false;
   octets[i]=Integer.parseInt(parts[i]);
   if(octets[i]>255)return false;
  }
  int a=octets[0],b=octets[1];
  return a==10||a==127||(a==172&&b>=16&&b<=31)||(a==192&&b==168)||(a==169&&b==254)||(a==100&&b>=64&&b<=127);
 }
 private static boolean validLimit(Long value){return value==null||value>0&&value<=100000000;}
 private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 List<Map<String,Object>> catalog(String org){List<Map<String,Object>> result=new ArrayList<>();for(var p:stored(org).providers)if(p.enabled&&p.hasKey)for(var m:p.models){Map<String,Object> row=new LinkedHashMap<>();row.put("id",m.id);row.put("object","model");row.put("owned_by",p.id);row.put("name",m.displayName);if(m.contextWindow!=null)row.put("context_window",m.contextWindow);if(m.maxOutputTokens!=null)row.put("max_output_tokens",m.maxOutputTokens);row.put("input_modalities",m.images?List.of("text","image"):List.of("text"));result.add(row);}return result;}
 Resolved resolve(String org,String model,String protocol){for(var p:stored(org).providers)if(p.enabled&&p.hasKey&&p.models.stream().anyMatch(m->m.id.equals(model))){if(!p.protocol.equals(protocol))bad("Choose the protocol configured for this model interface");return new Resolved(p.baseUrl,secrets.cryptOrganizationModel(false,org,p.apiKey));}throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Model is not allowed");}
}
