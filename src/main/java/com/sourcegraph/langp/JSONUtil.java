package com.sourcegraph.langp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class JSONUtil {

    private static Gson gson;

    static {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.setPrettyPrinting();
        gsonBuilder.disableHtmlEscaping();
        gson = gsonBuilder.create();
    }

    static void write(Object o, Appendable writer) {
        gson.toJson(o, writer);
    }

}