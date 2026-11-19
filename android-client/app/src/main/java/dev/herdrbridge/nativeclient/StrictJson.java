package dev.herdrbridge.nativeclient;

import org.json.*;
import java.util.HashSet;
import java.util.Set;

/** Strict JSON syntax plus duplicate-name/depth limits, before JSONObject can discard evidence. */
final class StrictJson {
    private final String text;private int position;
    private StrictJson(String text){this.text=text;}
    static JSONObject object(String raw)throws JSONException {
        if(raw==null||raw.length()>4*1024*1024)throw new JSONException("JSON size limit");
        StrictJson parser=new StrictJson(raw);Object result=parser.value(0);parser.space();
        if(!(result instanceof JSONObject)||parser.position!=raw.length())throw new JSONException("Invalid JSON object");
        return (JSONObject)result;
    }
    private void space(){while(position<text.length()&&" \n\r\t".indexOf(text.charAt(position))>=0)position++;}
    private boolean take(char c){space();if(position<text.length()&&text.charAt(position)==c){position++;return true;}return false;}
    private Object value(int depth)throws JSONException {
        if(depth>32)throw new JSONException("JSON depth limit");space();if(position>=text.length())throw bad();
        char c=text.charAt(position);
        if(c=='{'){
            position++;JSONObject object=new JSONObject();Set<String> keys=new HashSet<>();if(take('}'))return object;
            do{space();String key=string();if(!keys.add(key)||!take(':'))throw bad();object.put(key,value(depth+1));}while(take(','));
            if(!take('}'))throw bad();return object;
        }
        if(c=='['){position++;JSONArray array=new JSONArray();if(take(']'))return array;do{array.put(value(depth+1));}while(take(','));if(!take(']'))throw bad();return array;}
        if(c=='"')return string();
        int start=position;while(position<text.length()&&",]} \r\n\t".indexOf(text.charAt(position))<0)position++;
        String token=text.substring(start,position);
        if(token.equals("true"))return Boolean.TRUE;if(token.equals("false"))return Boolean.FALSE;if(token.equals("null"))return JSONObject.NULL;
        if(!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
        try{if(!token.contains(".")&&!token.contains("e")&&!token.contains("E"))return Long.parseLong(token);double d=Double.parseDouble(token);if(!Double.isFinite(d))throw bad();return d;}catch(NumberFormatException e){throw bad();}
    }
    private String string()throws JSONException {
        space();if(position>=text.length()||text.charAt(position++)!='"')throw bad();StringBuilder out=new StringBuilder();
        while(position<text.length()){
            char c=text.charAt(position++);if(c=='"')return out.toString();if(c<32)throw bad();
            if(c!='\\'){out.append(c);continue;}
            if(position>=text.length())throw bad();char e=text.charAt(position++);
            switch(e){case '"':case '\\':case '/':out.append(e);break;case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;
             case 'u':if(position+4>text.length())throw bad();String hex=text.substring(position,position+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw bad();out.append((char)Integer.parseInt(hex,16));position+=4;break;
             default:throw bad();}
        }throw bad();
    }
    private JSONException bad(){return new JSONException("Invalid JSON syntax or duplicate field");}
}
