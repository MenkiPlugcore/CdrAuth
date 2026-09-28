package id.menkiplugcore.cdrauth.storage;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public final class AccountStore {
    private final CdrAuthPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public AccountStore(CdrAuthPlugin plugin) throws IOException {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data directory");
        }
        this.file = new File(plugin.getDataFolder(), "accounts.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        if (!file.exists()) {
            save();
        }
    }

    public synchronized Optional<AccountRecord> find(UUID uuid) {
        return read(uuid.toString());
    }

    public synchronized Optional<AccountRecord> findByQuery(String query) {
        try {
            Optional<AccountRecord> byUuid = find(UUID.fromString(query));
            if (byUuid.isPresent()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
        }

        ConfigurationSection accounts = data.getConfigurationSection("accounts");
        if (accounts == null) {
            return Optional.empty();
        }

        for (String key : accounts.getKeys(false)) {
            String username = accounts.getString(key + ".username", "");
            if (username.equalsIgnoreCase(query)) {
                return read(key);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AccountRecord> findByIpHmac(String ipHmac) {
        ConfigurationSection accounts = data.getConfigurationSection("accounts");
        if (accounts == null || ipHmac == null || ipHmac.isBlank()) {
            return Optional.empty();
        }

        for (String key : accounts.getKeys(false)) {
            String stored = accounts.getString(key + ".ip-hmac");
            if (ipHmac.equals(stored)) {
                return read(key);
            }
        }
        return Optional.empty();
    }

    public synchronized void register(AccountRecord record) {
        String base = "accounts." + record.uuid();
        data.set(base + ".username", record.username());
        data.set(base + ".pin-salt", record.pinSalt());
        data.set(base + ".pin-hash", record.pinHash());
        data.set(base + ".ip-hmac", record.ipHmac());
        data.set(base + ".created-at", record.createdAt());
        saveUnchecked();
    }

    public synchronized void updateUsername(UUID uuid, String username) {
        if (data.contains("accounts." + uuid)) {
            data.set("accounts." + uuid + ".username", username);
            saveUnchecked();
        }
    }

    public synchronized Optional<AccountRecord> unregister(String query) {
        Optional<AccountRecord> account = findByQuery(query);
        account.ifPresent(record -> {
            data.set("accounts." + record.uuid(), null);
            saveUnchecked();
        });
        return account;
    }

    private Optional<AccountRecord> read(String key) {
        String base = "accounts." + key;
        if (!data.contains(base)) {
            return Optional.empty();
        }

        try {
            UUID uuid = UUID.fromString(key);
            String username = data.getString(base + ".username");
            String salt = data.getString(base + ".pin-salt");
            String hash = data.getString(base + ".pin-hash");
            String ipHmac = data.getString(base + ".ip-hmac");
            long createdAt = data.getLong(base + ".created-at");
            if (username == null || salt == null || hash == null || ipHmac == null) {
                return Optional.empty();
            }
            return Optional.of(new AccountRecord(uuid, username, salt, hash, ipHmac, createdAt));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ignoring invalid account UUID in accounts.yml: " + key);
            return Optional.empty();
        }
    }

    private void saveUnchecked() {
        try {
            save();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save accounts.yml", exception);
        }
    }

    private void save() throws IOException {
        data.save(file);
    }
}
