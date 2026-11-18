-- One PostgreSQL server locally, one database and owner per service (each service still owns its schema via Flyway).
CREATE USER inventory WITH PASSWORD 'inventory';
CREATE DATABASE inventory OWNER inventory;
CREATE USER orders WITH PASSWORD 'orders';
CREATE DATABASE orders OWNER orders;
CREATE USER payments WITH PASSWORD 'payments';
CREATE DATABASE payments OWNER payments;
CREATE USER catalog WITH PASSWORD 'catalog';
CREATE DATABASE catalog OWNER catalog;
