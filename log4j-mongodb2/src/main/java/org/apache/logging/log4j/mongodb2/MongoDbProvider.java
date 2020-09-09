/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.mongodb2;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.appender.nosql.NoSqlProvider;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAliases;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.convert.TypeConverters;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.ValidHost;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.ValidPort;
import org.apache.logging.log4j.core.filter.AbstractFilterable;
import org.apache.logging.log4j.core.util.NameUtil;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.LoaderUtil;
import org.apache.logging.log4j.util.Strings;

import com.mongodb.DB;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;

/**
 * The MongoDB implementation of {@link NoSqlProvider}.
 */
@Plugin(name = "MongoDb2", category = Core.CATEGORY_NAME, printObject = true)
@PluginAliases("MongoDb") // Deprecated alias
public final class MongoDbProvider implements NoSqlProvider<MongoDbConnection> {

    public static class Builder<B extends Builder<B>> extends AbstractFilterable.Builder<B>
			implements org.apache.logging.log4j.core.util.Builder<MongoDbProvider> {

		private static WriteConcern toWriteConcern(final String writeConcernConstant,
	            final String writeConcernConstantClassName) {
	        WriteConcern writeConcern;
	        if (Strings.isNotEmpty(writeConcernConstant)) {
	            if (Strings.isNotEmpty(writeConcernConstantClassName)) {
	                try {
	                    final Class<?> writeConcernConstantClass = LoaderUtil.loadClass(writeConcernConstantClassName);
	                    final Field field = writeConcernConstantClass.getField(writeConcernConstant);
	                    writeConcern = (WriteConcern) field.get(null);
	                } catch (final Exception e) {
	                    LOGGER.error("Write concern constant [{}.{}] not found, using default.",
	                            writeConcernConstantClassName, writeConcernConstant);
	                    writeConcern = DEFAULT_WRITE_CONCERN;
	                }
	            } else {
	                writeConcern = WriteConcern.valueOf(writeConcernConstant);
	                if (writeConcern == null) {
	                    LOGGER.warn("Write concern constant [{}] not found, using default.", writeConcernConstant);
	                    writeConcern = DEFAULT_WRITE_CONCERN;
	                }
	            }
	        } else {
	            writeConcern = DEFAULT_WRITE_CONCERN;
	        }
	        return writeConcern;
	    }

		@PluginBuilderAttribute
		@ValidHost
		private String server = "localhost";

		@PluginBuilderAttribute
		@ValidPort
		private String port = "" + DEFAULT_PORT;

		@PluginBuilderAttribute
		@Required(message = "No database name provided")
		private String databaseName;

		@PluginBuilderAttribute
		@Required(message = "No collection name provided")
		private String collectionName;

		@PluginBuilderAttribute
		private String userName;

		@PluginBuilderAttribute(sensitive = true)
		private String password;

		@PluginBuilderAttribute("capped")
		private boolean isCapped = false;

		@PluginBuilderAttribute
		private int collectionSize = DEFAULT_COLLECTION_SIZE;

		@PluginBuilderAttribute
		private String factoryClassName;

		@PluginBuilderAttribute
		private String factoryMethodName;

		@PluginBuilderAttribute
		private String writeConcernConstantClassName;

		@PluginBuilderAttribute
		private String writeConcernConstant;

		@Override
		public MongoDbProvider build() {
	        DB database;
	        String description;
	        if (Strings.isNotEmpty(factoryClassName) && Strings.isNotEmpty(factoryMethodName)) {
	            try {
	                final Class<?> factoryClass = LoaderUtil.loadClass(factoryClassName);
	                final Method method = factoryClass.getMethod(factoryMethodName);
	                final Object object = method.invoke(null);

	                if (object instanceof DB) {
	                    database = (DB) object;
	                } else if (object instanceof MongoClient) {
	                    if (Strings.isNotEmpty(databaseName)) {
	                        database = ((MongoClient) object).getDB(databaseName);
	                    } else {
	                        LOGGER.error("The factory method [{}.{}()] returned a MongoClient so the database name is "
	                                + "required.", factoryClassName, factoryMethodName);
	                        return null;
	                    }
	                } else if (object == null) {
	                    LOGGER.error("The factory method [{}.{}()] returned null.", factoryClassName, factoryMethodName);
	                    return null;
	                } else {
	                    LOGGER.error("The factory method [{}.{}()] returned an unsupported type [{}].", factoryClassName,
	                            factoryMethodName, object.getClass().getName());
	                    return null;
	                }

	                description = "database=" + database.getName();
	                final List<ServerAddress> addresses = database.getMongo().getAllAddress();
	                if (addresses.size() == 1) {
	                    description += ", server=" + addresses.get(0).getHost() + ", port=" + addresses.get(0).getPort();
	                } else {
	                    description += ", servers=[";
	                    for (final ServerAddress address : addresses) {
	                        description += " { " + address.getHost() + ", " + address.getPort() + " } ";
	                    }
	                    description += "]";
	                }
	            } catch (final ClassNotFoundException e) {
	                LOGGER.error("The factory class [{}] could not be loaded.", factoryClassName, e);
	                return null;
	            } catch (final NoSuchMethodException e) {
	                LOGGER.error("The factory class [{}] does not have a no-arg method named [{}].", factoryClassName,
	                        factoryMethodName, e);
	                return null;
	            } catch (final Exception e) {
	                LOGGER.error("The factory method [{}.{}()] could not be invoked.", factoryClassName, factoryMethodName,
	                        e);
	                return null;
	            }
	        } else if (Strings.isNotEmpty(databaseName)) {
	            final List<MongoCredential> credentials = new ArrayList<>();
	            description = "database=" + databaseName;
	            if (Strings.isNotEmpty(userName) && Strings.isNotEmpty(password)) {
	                description += ", username=" + userName + ", passwordHash="
	                        + NameUtil.md5(password + MongoDbProvider.class.getName());
	                credentials.add(MongoCredential.createCredential(userName, databaseName, password.toCharArray()));
	            }
	            try {
	                final int portInt = TypeConverters.convert(port, int.class, DEFAULT_PORT);
	                description += ", server=" + server + ", port=" + portInt;
	                database = new MongoClient(new ServerAddress(server, portInt), credentials).getDB(databaseName);
	            } catch (final Exception e) {
	                LOGGER.error(
	                        "Failed to obtain a database instance from the MongoClient at server [{}] and " + "port [{}].",
	                        server, port);
	                return null;
	            }
	        } else {
	            LOGGER.error("No factory method was provided so the database name is required.");
	            return null;
	        }

	        try {
	            database.getCollectionNames(); // Check if the database actually requires authentication
	        } catch (final Exception e) {
	            LOGGER.error(
	                    "The database is not up, or you are not authenticated, try supplying a username and password to the MongoDB provider.",
	                    e);
	            return null;
	        }

	        final WriteConcern writeConcern = toWriteConcern(writeConcernConstant, writeConcernConstantClassName);

	        return new MongoDbProvider(database, writeConcern, collectionName, isCapped, collectionSize, description);
		}

		public B setCapped(final boolean isCapped) {
			this.isCapped = isCapped;
			return asBuilder();
		}

		public B setCollectionName(final String collectionName) {
			this.collectionName = collectionName;
			return asBuilder();
		}

		public B setCollectionSize(final int collectionSize) {
			this.collectionSize = collectionSize;
			return asBuilder();
		}

		public B setDatabaseName(final String databaseName) {
			this.databaseName = databaseName;
			return asBuilder();
		}

		public B setFactoryClassName(final String factoryClassName) {
			this.factoryClassName = factoryClassName;
			return asBuilder();
		}

		public B setFactoryMethodName(final String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
			return asBuilder();
		}

		public B setPassword(final String password) {
			this.password = password;
			return asBuilder();
		}

		public B setPort(final String port) {
			this.port = port;
			return asBuilder();
		}

		public B setServer(final String server) {
			this.server = server;
			return asBuilder();
		}

		public B setUserName(final String userName) {
			this.userName = userName;
			return asBuilder();
		}

		public B setWriteConcernConstant(final String writeConcernConstant) {
			this.writeConcernConstant = writeConcernConstant;
			return asBuilder();
		}

	    public B setWriteConcernConstantClassName(final String writeConcernConstantClassName) {
			this.writeConcernConstantClassName = writeConcernConstantClassName;
			return asBuilder();
		}
    }
    private static final WriteConcern DEFAULT_WRITE_CONCERN = WriteConcern.ACKNOWLEDGED;
    private static final Logger LOGGER = StatusLogger.getLogger();
    private static final int DEFAULT_PORT = 27017;

    private static final int DEFAULT_COLLECTION_SIZE = 536870912;
    /**
     * Factory method for creating a MongoDB provider within the plugin manager.
     *
     * @param collectionName The name of the MongoDB collection to which log events should be written.
     * @param writeConcernConstant The {@link WriteConcern} constant to control writing details, defaults to
     *                             {@link WriteConcern#ACKNOWLEDGED}.
     * @param writeConcernConstantClassName The name of a class containing the aforementioned static WriteConcern
     *                                      constant. Defaults to {@link WriteConcern}.
     * @param databaseName The name of the MongoDB database containing the collection to which log events should be
     *                     written. Mutually exclusive with {@code factoryClassName&factoryMethodName!=null}.
     * @param server The host name of the MongoDB server, defaults to localhost and mutually exclusive with
     *               {@code factoryClassName&factoryMethodName!=null}.
     * @param port The port the MongoDB server is listening on, defaults to the default MongoDB port and mutually
     *             exclusive with {@code factoryClassName&factoryMethodName!=null}.
     * @param userName The username to authenticate against the MongoDB server with.
     * @param password The password to authenticate against the MongoDB server with.
     * @param factoryClassName A fully qualified class name containing a static factory method capable of returning a
     *                         {@link DB} or a {@link MongoClient}.
     * @param factoryMethodName The name of the public static factory method belonging to the aforementioned factory
     *                          class.
     * @return a new MongoDB provider.
     * @deprecated in 2.8; use {@link #newBuilder()} instead.
     */
    @Deprecated
    public static MongoDbProvider createNoSqlProvider(
            final String collectionName,
            final String writeConcernConstant,
            final String writeConcernConstantClassName,
            final String databaseName,
            final String server,
            final String port,
            final String userName,
            final String password,
            final String factoryClassName,
			final String factoryMethodName) {
    	LOGGER.info("createNoSqlProvider");
		return newBuilder().setCollectionName(collectionName).setWriteConcernConstant(writeConcernConstantClassName)
				.setWriteConcernConstant(writeConcernConstant).setDatabaseName(databaseName).setServer(server)
				.setPort(port).setUserName(userName).setPassword(password).setFactoryClassName(factoryClassName)
				.setFactoryMethodName(factoryMethodName).build();
	}
    @PluginBuilderFactory
	public static <B extends Builder<B>> B newBuilder() {
		return new Builder<B>().asBuilder();
	}
    private final String collectionName;
    private final DB database;
    private final String description;

    private final WriteConcern writeConcern;

    private final boolean isCapped;

    private final Integer collectionSize;

    private MongoDbProvider(final DB database, final WriteConcern writeConcern, final String collectionName,
            final boolean isCapped, final Integer collectionSize, final String description) {
        this.database = database;
        this.writeConcern = writeConcern;
        this.collectionName = collectionName;
        this.isCapped = isCapped;
        this.collectionSize = collectionSize;
        this.description = "mongoDb{ " + description + " }";
    }

	@Override
    public MongoDbConnection getConnection() {
        return new MongoDbConnection(this.database, this.writeConcern, this.collectionName, this.isCapped, this.collectionSize);
    }

	@Override
    public String toString() {
        return this.description;
    }
}
