package com.koper.koper_lib.scripting;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

// one per namespace; closed and replaced on every hot-reload
public class KoperPackClassLoader extends URLClassLoader {
    private final String namespace;

    public KoperPackClassLoader(Path classesDir, ClassLoader parent, String namespace) throws MalformedURLException {
        super(new URL[]{ classesDir.toUri().toURL() }, parent);
        this.namespace = namespace;
    }

    public String getNamespace() { return namespace; }
}
