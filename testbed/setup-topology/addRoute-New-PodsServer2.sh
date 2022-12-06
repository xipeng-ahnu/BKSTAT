#!/bin/sh
#192.168.1.119
sudo route add -host 192.168.1.104 dev ens33 metric 99
#192.168.1.113
sudo route add -host 192.168.1.139 dev ens38 metric 99
#192.168.1.118
sudo route add -host 192.168.1.109 dev ens39 metric 99
#192.168.1.116
sudo route add -host 192.168.1.134 dev ens40 metric 99
#192.168.1.117
sudo route add -host 192.168.1.110 dev ens41 metric 99
#192.168.1.115
sudo route add -host 192.168.1.133 dev ens42 metric 99
#192.168.1.112
sudo route add -host 192.168.1.107 dev ens43 metric 99
#192.168.1.114
sudo route add -host 192.168.1.135 dev ens44 metric 99

