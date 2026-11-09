package com.lsmom.crud.ddl;

import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** 把 meta_ddl_script 中的脚本作为 Flyway 迁移资源（V<id>__<描述>.sql）。 */
final class DbMigrationResources implements ResourceProvider {

    record Script(long id, String description, String sql) {
        String filename() {
            return "V" + id + "__" + description.replaceAll("[^A-Za-z0-9]+", "_") + ".sql";
        }
    }

    private final List<Script> scripts;

    DbMigrationResources(List<Script> scripts) {
        this.scripts = scripts;
    }

    @Override
    public LoadableResource getResource(String name) {
        return scripts.stream().filter(s -> s.filename().equals(name)).findFirst().map(DbMigrationResources::resource).orElse(null);
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return scripts.stream().filter(s -> s.filename().startsWith(prefix) && Arrays.stream(suffixes).anyMatch(x -> s.filename().endsWith(x)))
            .map(DbMigrationResources::resource).toList();
    }

    private static LoadableResource resource(Script s) {
        return new LoadableResource() {
            @Override
            public Reader read() {
                return new StringReader(s.sql());
            }

            @Override
            public String getAbsolutePath() {
                return "db:meta_ddl_script/" + s.filename();
            }

            @Override
            public String getAbsolutePathOnDisk() {
                return getAbsolutePath();
            }

            @Override
            public String getFilename() {
                return s.filename();
            }

            @Override
            public String getRelativePath() {
                return s.filename();
            }
        };
    }
}
