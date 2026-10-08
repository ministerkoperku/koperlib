package com.koper.koper_lib.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

// every soul value is one tagged string so the whole vault is just Map<uuid, Map<key,String>> — trivially
// Codec-able and trivially network-able. first char is the type tag, rest is the payload.
//   n<double>  number      b1 / b0   bool      s<text>  string      j<json>  table/list      x  tombstone (remove)
// keeping it flat means no per-value Codec dispatch and no NBT type juggling. pure, no MC deps — client uses it too.
public final class KoperSoulCodec {

    public static final String TOMBSTONE = "x"; // sent over the wire to tell the client "drop this key"

    private KoperSoulCodec() {}

    // lua value -> tagged string. null/json-null means "no value" -> caller treats as remove
    public static String encode(JsonElement v) {
        if (v == null || v.isJsonNull()) return null;
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean() ? "b1" : "b0";
            if (p.isNumber())  return "n" + p.getAsDouble();
            return "s" + p.getAsString();
        }
        // object or array -> stash raw json, get() rebuilds the table
        return "j" + v.toString();
    }

    // tagged string -> lua value (what scripts get back from koper.pstate.get)
    public static JsonElement decode(String tagged) {
        if (tagged == null || tagged.isEmpty()) return null;
        char t = tagged.charAt(0);
        String body = tagged.substring(1);
        return switch (t) {
            case 'b' -> new JsonPrimitive("1".equals(body));
            case 'n' -> numberPrim(body);
            case 'j' -> { try { yield JsonParser.parseString(body); } catch (Exception e) { yield new JsonPrimitive(body); } }
            default  -> new JsonPrimitive(body); // 's' and anything weird
        };
    }

    // whole numbers come back as longs so lua sees an integer, not 3.0
    private static JsonElement numberPrim(String body) {
        try {
            double d = Double.parseDouble(body);
            if (Double.isFinite(d) && d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15)
                return new JsonPrimitive((long) d);
            return new JsonPrimitive(d);
        } catch (NumberFormatException e) {
            return new JsonPrimitive(0);
        }
    }

    // typed peeks for java/client side that don't want to touch json
    public static double asNum(String tagged, double def) {
        if (tagged == null || tagged.isEmpty()) return def;
        if (tagged.charAt(0) == 'n') { try { return Double.parseDouble(tagged.substring(1)); } catch (NumberFormatException e) { return def; } }
        if (tagged.charAt(0) == 'b') return "b1".equals(tagged) ? 1 : 0;
        return def;
    }

    public static boolean asBool(String tagged, boolean def) {
        if (tagged == null || tagged.isEmpty()) return def;
        return switch (tagged.charAt(0)) {
            case 'b' -> "b1".equals(tagged);
            case 'n' -> asNum(tagged, 0) != 0;
            default  -> def;
        };
    }

    public static String asStr(String tagged) {
        return tagged == null || tagged.isEmpty() ? "" : tagged.substring(1);
    }

    // pack namespaces are [a-z0-9_] only — keeps "ns:key" splittable and matches how the lua addon names get cleaned
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') out.append(c);
        }
        return out.isEmpty() ? "default" : out.toString();
    }
}
