package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperBookData;
import com.koper.koper_lib.quest.BookShelf;

// books/<name>.json -> BookShelf. pure data like quests, so it reloads without a restart
public class BookFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) {
            KoperLib.LOGGER.warn("BookFactory: book without an id, skipped");
            return;
        }
        KoperBookData book = new KoperBookData();
        book.applyJson(json);
        BookShelf.put(book);
        KoperLib.LOGGER.info("BookFactory: Registered quest book: {}", book.id);
    }
}
