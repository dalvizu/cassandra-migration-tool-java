package io.smartcat.migration;

import io.smartcat.migration.exceptions.MigrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Session;

/**
 * Responsible for applying migrations to production - accepts as arguments an active
 * cassandra session and a VersionStrategy to determine what migrations to apply. Migrations
 * to apply are passed as arguments to the migration method.
 *
 */
public class MigrationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationEngine.class);

    private MigrationEngine() {
    }

    /**
     * Create migrator out of session fully prepared for doing migration of resources.
     * @param session Datastax driver session object
     * @return migrator instance with versioner and session which can migrate resources
     */
    public static Migrator withSession(final Session session) {
        return new Migrator(session);
    }

    /**
     * Migrator handles migrations and errors.
     */
    public static class Migrator {
        private final Session session;
        private final CassandraVersioner versioner;

        /**
         * Create new Migrator with active Cassandra session.
         * @param session Active Cassandra session
         */
        public Migrator(final Session session) {
            this.session = session;
            this.versioner = new CassandraVersioner();
            versioner.bootstrapSchemaVersionTable(session);
        }

        /**
         * Method that executes all migration from migration resources that are higher version than db version. If
         * migration fails, method will exit.
         *
         * @param resources Collection of migrations to be executed
         * @return Success of migration
         */
        public boolean migrate(final MigrationResources resources) {
            LOGGER.debug("Start migration");

            for (final Migration migration : resources.getMigrations()) {
                final MigrationType type = migration.getType();
                final Comparable migrationVersion = migration.getVersion();
                final int version = versioner.getCurrentVersion(session, migration.getType());

                LOGGER.info("Db is version {} for type {}.", version, type.name());
                LOGGER.info("Compare {} migration version {} with description {}", type.name(), migrationVersion,
                        migration.getDescription());

                if (migrationVersion.compareTo(version) <= 0) {
                    LOGGER.warn("Skipping migration [{}] with version {} since db is on higher version {}.",
                            migration.getDescription(), migrationVersion, version);
                    continue;
                }

                migration.setSession(session);

                final long start = System.currentTimeMillis();
                LOGGER.info("Start executing migration to version {}.", migrationVersion);

                try {
                    migration.execute();
                } catch (final MigrationException e) {
                    LOGGER.error("Failed to execute migration version {}, exception {}!", migrationVersion,
                            e.getMessage());
                    LOGGER.debug("Exception stack trace: {}", e);
                    return false;
                }

                final long end = System.currentTimeMillis();
                final long seconds = (end - start) / 1000;
                LOGGER.info("Migration [{}] to version {} finished in {} seconds.", migration.getDescription(),
                        migrationVersion, seconds);

                try {
                    versioner.markMigrationAsApplied(session, migration);
                } catch (final Exception e) {
                    LOGGER.error("Db schema update failed for migration version {}!", migrationVersion);
                    return false;
                }
            }

            return true;
        }
    }

}
