#!/bin/sh
#192.168.1.106
sudo route add -host 192.168.1.121 dev ens33 metric 99
#192.168.1.105
sudo route add -host 192.168.1.127 dev ens38 metric 99
#192.168.1.104
sudo route add -host 192.168.1.119 dev ens39 metric 99
#192.168.1.110
sudo route add -host 192.168.1.117 dev ens40 metric 99
#192.168.1.108
sudo route add -host 192.168.1.122 dev ens41 metric 99
#192.168.1.111
sudo route add -host 192.168.1.125 dev ens42 metric 99
#192.168.1.109
sudo route add -host 192.168.1.118 dev ens43 metric 99
#192.168.1.107
sudo route add -host 192.168.1.112 dev ens44 metric 99
