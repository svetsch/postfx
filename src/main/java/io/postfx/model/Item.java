package io.postfx.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Node of a collection tree: either a {@link Folder} (incl. {@link Collection}) or a {@link RequestItem}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Folder.class, name = "folder"),
        @JsonSubTypes.Type(value = Collection.class, name = "collection"),
        @JsonSubTypes.Type(value = RequestItem.class, name = "request")
})
public abstract class Item {
    public String id = Ids.newId();
    public String name = "";
    public String description;

    @Override
    public String toString() {
        return name;
    }
}
