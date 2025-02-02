/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.postgres;

import java.util.EnumSet;
import java.util.List;

import de.softwareforge.testing.postgres.junit5.EmbeddedPgExtension;
import de.softwareforge.testing.postgres.junit5.MultiDatabaseBuilder;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.generic.GenericType;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.sqlobject.SingleValue;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.testing.junit5.JdbiExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEnumSets {

    private static final GenericType<EnumSet<Platform>> PLATFORM_SET = new GenericType<EnumSet<Platform>>() {};

    @RegisterExtension
    public static EmbeddedPgExtension pg = MultiDatabaseBuilder.instanceWithDefaults().build();

    @RegisterExtension
    public JdbiExtension pgExtension = JdbiExtension.postgres(pg).withPlugins(new SqlObjectPlugin(), new PostgresPlugin())
        .withInitializer((ds, handle) -> handle.useTransaction(h -> {
            h.execute("drop table if exists videos");
            h.execute("create table videos (id int primary key, supported_platforms bit(5))");

            PreparedBatch batch = h.prepareBatch("insert into videos(id, supported_platforms) values (:id,:supported_platforms::varbit)");
            batch
                .bind("id", 0)
                .bindByType("supported_platforms", EnumSet.of(Platform.IOS, Platform.ANDROID, Platform.WEB), PLATFORM_SET)
                .add();
            batch
                .bind("id", 1)
                .bindByType("supported_platforms", EnumSet.of(Platform.SMART_TV), PLATFORM_SET)
                .add();
            batch
                .bind("id", 2)
                .bindByType("supported_platforms", EnumSet.of(Platform.ANDROID, Platform.STB), PLATFORM_SET)
                .add();
            batch
                .bind("id", 3)
                .bindByType("supported_platforms", EnumSet.of(Platform.IOS, Platform.WEB), PLATFORM_SET)
                .add();
            batch
                .bind("id", 4)
                .bindByType("supported_platforms", EnumSet.noneOf(Platform.class), PLATFORM_SET)
                .add();
            batch
                .bind("id", 5)
                .bindByType("supported_platforms", null, PLATFORM_SET)
                .add();
            batch.execute();
        }));

    private VideoDao videoDao;

    @BeforeEach
    public void setupDbi() {
        videoDao = pgExtension.attach(VideoDao.class);
    }

    @Test
    public void testInserts() {
        videoDao.insert(6, EnumSet.of(Platform.IOS, Platform.ANDROID));
        assertThat(getSupportedPlatforms(6)).containsExactly(Platform.ANDROID, Platform.IOS);
    }

    @Test
    public void testInsertsEmpty() {
        videoDao.insert(7, EnumSet.noneOf(Platform.class));
        assertThat(getSupportedPlatforms(7)).isEmpty();
    }

    @Test
    public void testInsertsNull() {
        videoDao.insert(8, null);
        assertThat(getSupportedPlatforms(8)).isNull();
    }

    @Test
    public void testReads() {
        EnumSet<Platform> supportedPlatforms = videoDao.getSupportedPlatforms(0);
        assertThat(supportedPlatforms).containsOnly(Platform.ANDROID, Platform.IOS, Platform.WEB);
    }

    @Test
    public void testReadsEmpty() {
        EnumSet<Platform> supportedPlatforms = videoDao.getSupportedPlatforms(4);
        assertThat(supportedPlatforms).isEmpty();
    }

    @Test
    public void testReadsNull() {
        EnumSet<Platform> supportedPlatforms = videoDao.getSupportedPlatforms(5);
        assertThat(supportedPlatforms).isNull();
    }

    @Test
    public void testBitwiseWorksForNoneElements() {
        List<Integer> notNullVideos = videoDao.getSupportedVideosOnPlatforms(EnumSet.noneOf(Platform.class));
        assertThat(notNullVideos).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    public void testBitwiseWorksForOneElement() {
        List<Integer> stbVideos = videoDao.getSupportedVideosOnPlatforms(EnumSet.of(Platform.STB));
        assertThat(stbVideos).containsOnlyOnce(2);
    }

    @Test
    public void testBitwiseWorksForSeveralElements() {
        List<Integer> webIosVideos = videoDao.getSupportedVideosOnPlatforms(EnumSet.of(Platform.WEB, Platform.IOS));
        assertThat(webIosVideos).containsExactly(0, 3);
    }

    @Test
    public void testBitwiseAdditionWorks() {
        videoDao.addPlatforms(1, EnumSet.of(Platform.IOS, Platform.ANDROID));
        EnumSet<Platform> supportedPlatforms = getSupportedPlatforms(1);
        assertThat(supportedPlatforms).containsExactly(Platform.ANDROID, Platform.IOS, Platform.SMART_TV);
    }

    @Test
    public void testBitwiseRemovingWorks() {
        videoDao.removePlatforms(0, EnumSet.of(Platform.IOS, Platform.ANDROID, Platform.SMART_TV));
        EnumSet<Platform> supportedPlatforms = getSupportedPlatforms(0);
        assertThat(supportedPlatforms).containsOnlyOnce(Platform.WEB);
    }

    @Test
    public void testAmountPlatforms() {
        int amount = videoDao.getAmountOfSupportedPlatforms(0);
        assertThat(amount).isEqualTo(3);
    }

    @Test
    public void throwsOnNonBitChars() {
        Handle handle = pgExtension.openHandle();
        // redefine column to varchar type
        handle.execute("drop table if exists videos");
        handle.execute("create table videos (id int primary key, supported_platforms varchar)");
        handle.execute("discard all");
        handle.useTransaction(h -> {
            // insert wrong bitstring
            int id = 1;
            String notBit = "2";
            h.createUpdate("insert into videos(id, supported_platforms) values (:id, :notBits)")
                .bind("id", id)
                .bind("notBits", "0101" + notBit)
                .execute();

            assertThatThrownBy(() -> h.attach(VideoDao.class).getSupportedPlatforms(id))
                .hasMessageContaining("non-bit character " + notBit);
        });
    }

    private EnumSet<Platform> getSupportedPlatforms(int id) {
        return pgExtension.openHandle()
            .createQuery("select supported_platforms from videos where id=:id")
            .bind("id", id)
            .mapTo(PLATFORM_SET)
            .one();
    }

    public interface VideoDao {

        @SqlUpdate("insert into videos(id, supported_platforms) values (:id, :platforms::varbit)")
        void insert(int id, EnumSet<Platform> platforms);

        @SqlQuery("select supported_platforms from videos where id=:id")
        @SingleValue
        EnumSet<Platform> getSupportedPlatforms(int id);

        @SqlQuery("select id from videos "
            + "where (supported_platforms & :platforms::varbit) = :platforms::varbit "
            + "order by id")
        List<Integer> getSupportedVideosOnPlatforms(EnumSet<Platform> platforms);

        @SqlUpdate("update videos "
            + "set supported_platforms = (supported_platforms | :platforms::varbit) "
            + "where id=:id")
        void addPlatforms(int id, EnumSet<Platform> platforms);

        @SqlUpdate("update videos "
            + "set supported_platforms = (supported_platforms & ~:platforms::varbit) "
            + "where id=:id")
        void removePlatforms(int id, EnumSet<Platform> platforms);

        @SqlQuery("select length(replace(supported_platforms::varchar, '0', '')) from videos "
            + "where id=:id")
        int getAmountOfSupportedPlatforms(int id);
    }

    public enum Platform {
        ANDROID,
        IOS,
        SMART_TV,
        STB,
        WEB
    }
}
