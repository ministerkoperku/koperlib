package com.koper.koper_lib.api;

import java.util.List;

public interface RuntimeAPI {
    boolean engineLoaded();
    String engineVersion();
    boolean featureOn(String featureId);
    List<String> snapshot();
}
