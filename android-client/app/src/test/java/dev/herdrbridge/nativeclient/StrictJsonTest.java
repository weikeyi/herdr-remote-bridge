package dev.herdrbridge.nativeclient;
import org.junit.Test;import static org.junit.Assert.*;
public class StrictJsonTest {
 @Test public void rejectsDuplicateLooseAndTrailing()throws Exception{
  for(String raw:new String[]{"{\"x\":1,\"x\":2}","{\"x\":1,\"\\u0078\":2}","{'x':1}","{\"x\":01}","{\"x\":NaN}","{\"x\":1,}","{} {}","{\"x\":[1,]}"}){
   try{StrictJson.object(raw);fail("accepted invalid JSON");}catch(org.json.JSONException expected){}
  }
  assertEquals(2,StrictJson.object("{\"a\":[true,null],\"n\":2}").getInt("n"));
 }
}
