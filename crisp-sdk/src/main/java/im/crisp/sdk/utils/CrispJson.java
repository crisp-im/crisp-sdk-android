package im.crisp.sdk.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.Date;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class CrispJson {
    private static Gson gson =
            new GsonBuilder()
                    .registerTypeAdapter(Date.class, new JsonDeserializer<Date>() {
                        public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                            return new Date(json.getAsJsonPrimitive().getAsLong());
                        }
                    })
                    .registerTypeAdapter(Date.class, new JsonSerializer<Date>() {
                        @Override
                        public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
                            return new JsonPrimitive(src.getTime());
                        }
                    })
                    .create();

    public static JSONObject GsonToJSON(Object gsonObject) {
        JSONObject json = null;
        try {
            return new JSONObject(gson.toJson(gsonObject));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static Gson getGson() {
        return gson;
    }
}
