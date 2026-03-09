package de.novium.dev.tempchannel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Holds the runtime state of a single temporary voice channel.
 * All fields are thread-safe by design – this object may be read
 * and written from multiple threads concurrently.
 */
public class TempChannelData {

    private final long channelId;
    private final TempChannelType type;

    private final AtomicBoolean activated = new AtomicBoolean(false);

    private final AtomicLong    creatorId    = new AtomicLong(0);
    private final AtomicBoolean locked       = new AtomicBoolean(false);

    private final AtomicInteger channelNumber = new AtomicInteger(0);

    /** Members who are allowed to join even when the channel is locked. */
    private final Set<Long> whitelist = ConcurrentHashMap.newKeySet();

    /** ID of the management panel message (in the panel channel). */
    private volatile String panelMessageId;

    public TempChannelData(long channelId, TempChannelType type) {
        this.channelId = channelId;
        this.type      = type;
    }



    /**
     * Tries to activate the channel for the given creator.
     * Returns {@code true} only for the thread that wins the race.
     */
    public boolean activate(long newCreatorId) {
        if (activated.compareAndSet(false, true)) {
            creatorId.set(newCreatorId);
            return true;
        }
        return false;
    }

    /**
     * Tries to deactivate the channel (reset to free state).
     * Returns {@code true} only for the thread that wins the race.
     */
    public boolean deactivate() {
        if (activated.compareAndSet(true, false)) {
            creatorId.set(0);
            locked.set(false);
            whitelist.clear();
            panelMessageId = null;
            return true;
        }
        return false;
    }

    public boolean isActivated() {
        return activated.get();
    }



    public long            getChannelId()      { return channelId; }
    public TempChannelType getType()           { return type; }
    public long            getCreatorId()      { return creatorId.get(); }
    public boolean         isLocked()          { return locked.get(); }
    public Set<Long>       getWhitelist()      { return whitelist; }
    public String          getPanelMessageId() { return panelMessageId; }
    public int             getChannelNumber()  { return channelNumber.get(); }

    public void setCreatorId(long id)            { creatorId.set(id); }
    public void setLocked(boolean value)         { locked.set(value); }
    public void setPanelMessageId(String msgId)  { panelMessageId = msgId; }
    public void setChannelNumber(int number)     { channelNumber.set(number); }



    public void    addToWhitelist(long memberId)      { whitelist.add(memberId); }
    public void    removeFromWhitelist(long memberId) { whitelist.remove(memberId); }
    public boolean isWhitelisted(long memberId)       { return whitelist.contains(memberId); }


    public String getWhitelistAsString() {
        return whitelist.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }


    public void loadWhitelistFromString(String raw) {
        whitelist.clear();
        if (raw == null || raw.isBlank()) return;
        for (String part : raw.split(",")) {
            try {
                whitelist.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) { }
        }
    }

    @Override
    public String toString() {
        return "TempChannelData{channelId=" + channelId
                + ", type=" + type
                + ", number=" + channelNumber.get()
                + ", activated=" + activated.get()
                + ", locked=" + locked.get() + "}";
    }
}
