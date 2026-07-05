-- Runs on first init of the postgres-payment container (docker-entrypoint-initdb.d).
-- The reactive twin gets its own database so benchmarks never share state with the MVC service.
CREATE DATABASE payment_reactive;
