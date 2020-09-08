package edu.harvard.iq.dataverse.util.json;

import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

public class JSONLDUtil {

	private static final Logger logger = Logger.getLogger(JSONLDUtil.class.getCanonicalName());
	
	public static Map<String, String> populateContext(JsonValue json) {
		Map <String, String> context = new TreeMap<String, String>();
		if(json instanceof JsonArray) {
			logger.warning("Array @context not yet supported");
		} else {
			for(String key: ((JsonObject)json).keySet()) {
				context.putIfAbsent(key, ((JsonObject)json).getString(key));
			}
		}
		return context;
	}
	public static JsonObject getContext(Map<String, String> contextMap) {
		JsonObjectBuilder contextBuilder = Json.createObjectBuilder();
		for(Entry<String, String> e: contextMap.entrySet()) {
			contextBuilder.add(e.getKey(), e.getValue());
		}
		return contextBuilder.build();
	}

}
