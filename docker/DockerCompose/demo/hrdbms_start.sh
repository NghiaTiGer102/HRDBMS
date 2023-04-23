docker-compose start
docker exec --user hrdbms hrdbms_coordinator java -cp /home/hrdbms/HRDBMS.jar: StartDB
