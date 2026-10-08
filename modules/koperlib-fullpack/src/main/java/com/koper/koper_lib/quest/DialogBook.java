package com.koper.koper_lib.quest;

import com.koper.koper_lib.data.KoperDialogData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// every dialogue that exists. wiped on reload, refilled by DialogFactory, same as the rest
public final class DialogBook {
    private static final Map<String, KoperDialogData> TALKS = new LinkedHashMap<>();

    private DialogBook() {}

    public static void put(KoperDialogData dialog) {
        if (dialog != null && dialog.id != null) TALKS.put(dialog.id, dialog);
    }

    public static KoperDialogData get(String id) { return id == null ? null : TALKS.get(id); }

    public static Collection<KoperDialogData> all() { return TALKS.values(); }

    public static Set<String> ids() { return TALKS.keySet(); }

    public static void clear() { TALKS.clear(); }

    // who talks when you right click this mob. first match wins, so put the specific one first
    public static KoperDialogData forSpeaker(String entityId, String scoreboardTags) {
        for (KoperDialogData dialog : TALKS.values())
            if (QuestChase.isGiver(dialog.speaker, entityId, scoreboardTags)) return dialog;
        return null;
    }
}
