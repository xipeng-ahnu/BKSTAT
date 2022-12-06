#!/bin/bash

serverIP=10.0.0.1
serverPort=5001
start=2000
end=2750


if [ $# -lt 2 ]; then
    echo "Too Less Arguments, Usage: ./iperfClient.sh IPERF-SERVER SERVER-LISTEN-PORT"
    exit
else
    serverIP=$1
    serverPort=$2
    if [ $# -ge 3 ];then
        start=$3
    fi
    if [ $# -eq 4 ];then
        end=$4
    fi
fi

for i in `seq $start 1 $end`; do
    iperf -c $serverIP -p $serverPort -d -L $i -t 0.1
done


