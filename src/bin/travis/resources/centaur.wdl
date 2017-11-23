task centaur {
    String centaur_branch
    File conf
    File pem
    File cromwell_jar
    File token
    String? integration_tests_dir

    command<<<
        # start mysql server
        mysqld &
        
        # give time to server to start
        git clone https://github.com/vishnubob/wait-for-it.git
        cd wait-for-it
        ./wait-for-it.sh -t 10 localhost:3306
        if (($? != 0)); then
            echo "Timed out waiting for mysql server. Exiting."
            exit 1
        fi
        
        cd ..
        
        # setup mysql
        mysql -u root -e "SET GLOBAL sql_mode = 'STRICT_ALL_TABLES';"
        mysql -u root -e "CREATE DATABASE IF NOT EXISTS cromwell_test;"
        mysql -u root -e "CREATE USER 'travis'@'localhost' IDENTIFIED BY '';"
        mysql -u root -e "GRANT ALL PRIVILEGES ON cromwell_test . * TO 'travis'@'localhost';"
        
        mkdir -p /cromwell_root/tmp/ivy2
        export SBT_OPTS=-Dsbt.ivy.home=/cromwell_root/tmp/.ivy2
        echo "current directory $PWD"
        git clone --branch=${centaur_branch} https://github.com/broadinstitute/cromwell.git

        cd cromwell

        centaur/test_cromwell.sh -j${cromwell_jar} -c${conf} -r/cromwell_root/cromwell -t${token} -elocaldockertest -p100 ${ "-i" + integration_tests_dir}
        EXIT_CODE=$?
        sbt coverageReport
        sbt coverageAggregate
        tar czf cromwell_target_dir.tgz target
        exit $EXIT_CODE
    >>>

    output {
       File cromwell_log = "cromwell/logs/cromwell.log"
       File centaur_log_file = "cromwell/logs/centaur.log"
       File cromwell_target_dir = "cromwell/cromwell_target_dir.tgz"
    }

    runtime {
        docker: "us.gcr.io/broad-dsde-cromwell-dev/centaur:latest" 
        cpu: "8"
        zones: "us-central1-b"
        failOnStderr: false
    }
}
workflow centaur_workflow {
    call centaur
}

