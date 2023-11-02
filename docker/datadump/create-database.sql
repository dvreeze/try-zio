-- Note that some placeholder names are replaced by environment variable values
CREATE DATABASE IF NOT EXISTS MYSQL_DATABASE;
CREATE USER 'MYSQL_USER'@'localhost' IDENTIFIED BY 'MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON MYSQL_DATABASE.* TO 'MYSQL_USER'@'localhost';
