package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperDialogData;
import com.koper.koper_lib.quest.DialogBook;

// dialogs/<name>.json -> DialogBook
public class DialogFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) {
            KoperLib.LOGGER.warn("DialogFactory: dialog without an id, skipped");
            return;
        }
        KoperDialogData dialog = new KoperDialogData();
        dialog.applyJson(json);

        if (dialog.nodes.isEmpty()) {
            KoperLib.LOGGER.warn("DialogFactory: '{}' has no nodes, nothing would be said", dialog.id);
            return;
        }
        if (dialog.node(dialog.start) == null)
            KoperLib.LOGGER.warn("DialogFactory: '{}' starts at '{}' which does not exist",
                dialog.id, dialog.start);

        DialogBook.put(dialog);
        KoperLib.LOGGER.info("DialogFactory: Registered dialog: {} ({} nodes)", dialog.id, dialog.nodes.size());
    }
}
