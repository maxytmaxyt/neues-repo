package de.novium.dev.tempchannel;

public enum TempChannelType {

    ONE("1er Talk", 1),
    TWO("2er Talk", 2),
    THREE("3er Talk", 3),
    FIVE("5er Talk", 5);

    private final String displayName;
    private final int userLimit;

    TempChannelType(String displayName, int userLimit) {
        this.displayName = displayName;
        this.userLimit = userLimit;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getUserLimit() {
        return userLimit;
    }

    public static TempChannelType fromName(String name) {
        for (TempChannelType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
