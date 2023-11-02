# Look at https://www.baeldung.com/ops/docker-mysql-container and https://hub.docker.com/_/mysql/

FROM mysql:5.7

MAINTAINER dvreeze

RUN chown -R mysql:root /var/lib/mysql/

# The following args are passed in the "docker build" command using "--build-arg" command arguments
# Example (for only one of the arguments):
# docker build --build-arg MYSQL_DATABASE=wordpress -f ./docker/db.Dockerfile ./docker

ARG MYSQL_DATABASE
ARG MYSQL_USER
ARG MYSQL_PASSWORD
ARG MYSQL_ROOT_PASSWORD
ARG MYSQL_PORT

# Root password is required
# The database schema ($MYSQL_DATABASE) will be created (the first time)
# The user and password are for an account that has all rights to that database schema

ENV MYSQL_DATABASE=$MYSQL_DATABASE
ENV MYSQL_USER=$MYSQL_USER
ENV MYSQL_PASSWORD=$MYSQL_PASSWORD
ENV MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD
ENV MYSQL_PORT=$MYSQL_PORT

RUN mkdir -p /datadump
COPY datadump/create-database.sql /datadump/1-create-database.sql
COPY datadump/wordpress-dump.sql /datadump/2-wordpress-dump.sql

# Filling in the environment variables by string replacements
RUN sed -i "s/MYSQL_DATABASE/$MYSQL_DATABASE/g" /datadump/1-create-database.sql
RUN sed -i "s/MYSQL_USER/$MYSQL_USER/g" /datadump/1-create-database.sql
RUN sed -i "s/MYSQL_PASSWORD/$MYSQL_PASSWORD/g" /datadump/1-create-database.sql

RUN sed -i "s/MYSQL_DATABASE/$MYSQL_DATABASE/g" /datadump/2-wordpress-dump.sql

# This will run those data scripts the first time; that's how directory docker-entrypoint-initdb.d is used automatically
RUN cp /datadump/1-create-database.sql /docker-entrypoint-initdb.d
RUN cp /datadump/2-wordpress-dump.sql /docker-entrypoint-initdb.d

EXPOSE $MYSQL_PORT
