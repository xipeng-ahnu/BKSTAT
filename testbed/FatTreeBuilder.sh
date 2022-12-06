#!/bin/sh
<<!!COMMENT!!
 ============== Introduction For The Whole Simulation Toponology ===================
 This script builds 3-Layers Fat Tree topology
 in a 3-Layers Fat Tree
 podNum supposed to be 2 exp k(not necessary, but easy to simulate in our lab reality),
 it will have podNum/2 aggregate switches and podNum/2 edge switches each pod
 every edge switch connect to every aggregate switch in one pod
 every aggregate switch connect to every group of core switches
 groupNum = podNum / 2
 in each core switch group, has (podNum / 2) core switches
 so, total      core switch num = (podNum / 2) * (podNum / 2)
     total aggregate switch num = podNum * (podNum / 2)
     total      edge switch num = podNum * (podNum / 2)
     total switch num = podNum * podNum * 5/4
     total   host num = podNum * podNum * podNum / 4
 
 the simulation use 4 server/pc to support the whole topo
 - For Core Switches, devided into two symmetric part
 Server c1: core switch part 1, with Master controller on it
 Server c2: core switch part 2, with Slave controller on it
 - For Aggregate and Edge Switches, devided into two symmetric part of pods
 Server a1: first half of the pods
 Server a2: second half of the pods 
 
 Naming Scheme:
 - For Core Switches:
 name: c i-g j-cs k  i: physical Server i;  j: core switches group j; k: core switch k in the j group
 dpid: between 1 - 999
 #mac: 00:01:i:j:k:portNo  i,j,k in [00..FF] each has 256 choise, the 2rd byte 01 means core switch
 eg. c2-g2-cs1 means CoreSwitch cs1 in core group 2 is running on physical Server c2
     port mac: 00:01:02:02:01:xx
 
 - For Aggr Switches:
 name: a i-p j-as k  i: physical Server i;  j: pod j;  k: aggregate switch k in pod j
 dpid: between 1001 - 1999
 #mac: 00:02:i:j:k:portNo  i,j,k in [00..FF] each has 256 choise, the 2rd byte 02 means aggr switch
 eg. a2-p7-as3 means AggrSwitch as3 in pod 7 is running on physical Server a2
      port mac: 00:02:02:07:03:xx
 
 - For Edge Switches:
 name: a i-p j-es k  i: physical Server i;  j: pod j;  k: edge switch k in pod j
 dpid: between 2001 - 2999
 #mac: 00:03:i:j:k:portNo  i,j,k in [00..FF] each has 256 choise, the 2rd byte 03 means edge switch
 eg. a2-p7-es3 means EdgeSwitch es3 in pod 7 is running on physical Server a2
      port mac: 00:03:02:07:03:xx
    
 - For Host:
 name: h-k  k: host k with port h-k_eth1
 mac: 00:00:00:i:j:k  i,j,k in [00..FF] each has 256 choise, means pod i edge switch j host k
 eg. h-16 means the 16th host , port name: h-16_eth1, port mac: 00:00:00:04:02:02 in pods=4 FatTree
      
 WARNING: due to limited port desc size in OpenFlow packet (16 byte), all '-' in name is ommited in the real building process
      
 for each switch, port max number is 254
 for each pod, AggrSwitch max number is 255, EdgeSwitch number is 255
 for each core group, CoreSwitch max number is 255
 
 ============== Introduction For This Script ===================
 This script build a quarter of the whole topology (1/4)
 Usage:
     sudo ./FatTreeBuilder.sh -n podNum -t serverType -c partNum       
 Param:
     -n:  pods number, default is 4
          warning: 1) it will always set to an integer square of 2, larger than your input if not
                      eg. if you input '-n 10' than 16 will be applied to n
                   2) if n is not 4, you MUST change the gre tunnel configuration below to match end points
                      to match each gre tunnel ends
     -t:  which type of machine you are running on, Core Switch Server or Points Server
          1: Core Switch Server: means running half of the core switches in groups
          2: Pods Switch Server: means running half of the aggr, edge and host in pods
     -c:  which half to build 
          1: First half of Core Switches or Pods
          2: Second(Last) half of Core Switches or Pods
 Example:  sudo ./FatTreeBuilder.sh -n 4 -t 1 -c 2
 	           builds a part of Fat Tree with 4 pods on a machine 
 	           contains 2 core server as the second part of the Core Switches
 Whole: you must run the script with certain configuration on 4 machines
        with -n podNum -t 1 -c 1 for machine1 as Core Server 1
             -n podNum -t 1 -c 2 for machine2 as Core Server 2
             -n podNum -t 2 -c 1 for machine3 as Pods Server 1
             -n podNum -t 2 -c 2 for machine4 as Pods Server 2               
!!COMMENT!!

# ===========================================
# Clear All Namespaces
# ===========================================
sudo ip -all netns del #only affect ubuntu 16.04 version or upper

# ===========================================
# Controller Settings
# ===========================================
c1_ip="192.168.1.253"
c2_ip="192.168.1.254"
masterController="tcp:$c1_ip:6653"
slaveController="tcp:$c2_ip:6654"

# ============================================
# GRE tunnel pair ip configuration, edit this part before start
# these configuration is defined to pair the GRE tunnel end point
# below is a static configuration set for pods = 4
# ============================================

# core switch 1 group 1 machine 1
c1g1cs1_i1_ip=192.168.1.106
a1p1as1_i3_ip=192.168.1.121

c1g1cs1_i2_ip=192.168.1.105
a1p2as1_i3_ip=192.168.1.127

c1g1cs1_i3_ip=192.168.1.104
a2p3as1_i3_ip=192.168.1.119

c1g1cs1_i4_ip=192.168.1.110
a2p4as1_i3_ip=192.168.1.117

# core switch 2 group 1 machine 1
c1g1cs2_i1_ip=192.168.1.108
a1p1as2_i3_ip=192.168.1.122

c1g1cs2_i2_ip=192.168.1.111
a1p2as2_i3_ip=192.168.1.125

c1g1cs2_i3_ip=192.168.1.109
a2p3as2_i3_ip=192.168.1.118

c1g1cs2_i4_ip=192.168.1.107
a2p4as2_i3_ip=192.168.1.112

# core switch 1 group 2 machine 2
c2g2cs1_i1_ip=192.168.1.132
a1p1as1_i4_ip=192.168.1.126

c2g2cs1_i2_ip=192.168.1.129
a1p2as1_i4_ip=192.168.1.123

c2g2cs1_i3_ip=192.168.1.139
a2p3as1_i4_ip=192.168.1.113

c2g2cs1_i4_ip=192.168.1.133
a2p4as1_i4_ip=192.168.1.115

# core switch 2 group 2 machine 2
c2g2cs2_i1_ip=192.168.1.131
a1p1as2_i4_ip=192.168.1.124

c2g2cs2_i2_ip=192.168.1.130
a1p2as2_i4_ip=192.168.1.128

c2g2cs2_i3_ip=192.168.1.134
a2p3as2_i4_ip=192.168.1.116

c2g2cs2_i4_ip=192.168.1.135
a2p4as2_i4_ip=192.168.1.114

# ====================================
# Data-path Id Range for each type of switch
# ====================================
cs_dpid_start=0 # (1 - 999) core switch
as_dpid_start=1000 # (1001 - 1999) aggregate switch
es_dpid_start=2000 # (2001 - 2999) edge switch

# =====================================
# Build Configuration Parameters
# =====================================
podNum=4
buildType=0 # 0 means unknown, 1 means core switch side server, 2 means pods server
buildServerNum=0 # 0 means unknown, 1 or 2 means the server number

# ======================================
# Global Functions
# ======================================

# translate decimal dpid like 1001 to hex dpid with 0x00000000000003e9
# usage: a=$(dectohex 1001)
dec2hex(){
    printf "%016x" $1
}

dec2hex2(){
	printf "%02x" $1
}

hex2dec(){
	printf "%d" $1
}
displayHelp(){
    echo "This script build a part of fat tree topology\nIt has 4 server parts, 2 core switch parts, 2 pods switch parts"
    echo "You must set the server type with option \"-t\" and the server num with option \"-c\""
    echo "Default pod number is 4"
    echo "Usage:\n   -h  display help info\n   -n  set pod numbers,must be an integer square of 2, default value is 4 "
    echo "   -t  set Server type to build\n       1:  core switch server\n       2:  pods switch server "
    echo "   -c  set Server number\n       1:  first server part\n       2:  second server part "
}

# ================================
# Treat User Input for initialization
# ================================
while getopts ":hn:t:c:" opt; do
        case $opt in
                h) # print help message
                   displayHelp
                   exit 1;
                   ;;
                n) # calculate pod number, find the nearest number 
                   # which is an integer square of 2 larger than user input
                   temp=0
                   echo $OPTARG|[ -n "`sed -n '/^[1-9][0-9]*$/p'`" ] && temp=$OPTARG
                   if [ $temp -gt 4 ]; then
                       t=$(($temp / 2))
                       c=1
                       rt=4
                       while [ $t -ne 0 ]; do
                           t=$(($t / 2))
                           c=$(($c + 1))
                           if [ $c -gt 2 ]; then
                               rt=$(($rt * 2))
                           fi
                       done
                       podNum=$rt
                       #echo $podNum                       
                   fi;;
                t) # set type for the server, 1 means core switch server, 2 means pods switch server
                   echo $OPTARG|[ -n "`sed -n '/^[1-9][0-9]*$/p'`" ] && buildType=$OPTARG
                   if [ $buildType -lt 1 -o $buildType -gt 2 ]; then
                       echo "Error: Server Type can only be set to 1 or 2 "
                       exit 1;
                   fi
                   ;;
                c) #set server num, 1 means first server part, 2 means second server part
                   echo $OPTARG|[ -n "`sed -n '/^[1-9][0-9]*$/p'`" ] && buildServerNum=$OPTARG
                   if [ $buildServerNum -lt 1 -o $buildServerNum -gt 2 ]; then
                       echo "Error: Server Number can only be set to 1 or 2 "
                       exit 1
                   fi
                   ;;
                *) # any other options give the help message
                   echo "Error: wrong option identifier! Please check help message below to use this script:"
                   displayHelp
                   exit 1
                   ;;
        esac
done
#通过shift $(($OPTIND - 1))的处理，$*中就只保留了除去选项内容的参数，可以在其后进行正常的shell编程处理了。
shift $(($OPTIND - 1))
if [ "$bulidType" = "0" -o "$buildServerNum" = "0" ]; then
	echo "Error: to run this script, you must use option \"-t\" to set the server Type and \"-c\" to set the server Number "
	exit 1
fi

# build parameters initialization
# define server prefix m, c1,c2 for core switch server, a1,a2 for pods switch server
bType="none"
machineCount=0
if [ $buildType -eq 1 ]; then
	bType="coreServer"
	if [ $buildServerNum -eq 1 ]; then
		m="c1"
		machineCount=1
	elif [ $buildServerNum -eq 2 ]; then
	    m="c2"
	    machineCount=2
    else
    	echo "Error Server Number: must be 1 or 2. Exit...."
    	exit 2
	fi
elif [ $buildType -eq 2 ]; then
	bType="podsServer"
	if [ $buildServerNum -eq 1 ]; then
		m="a1"
		machineCount=1
	elif [ $buildServerNum -eq 2 ]; then
	    m="a2"
	    machineCount=2
    else
    	echo "Error Server Number: must be 1 or 2. Exit...."
    	exit 2
	fi
else
	echo "Error Server Type: must be 1(core server) or 2(pods server. Exit....)"
	exit 2
fi

# ========================================
# building block for Core Server Machine
# ========================================
if [ "$bType" = "coreServer" ]; then	
	# create (podNum / 4) groups with (podNum / 2) core switches each group
    groupStart=$(( $(($machineCount - 1)) * $(($podNum / 4)) + 1))
    groupEnd=$(( $(($machineCount)) * $(($podNum / 4)) ))
	# group id from groupStart to groupEnd
	for i in $(seq $groupStart $groupEnd ); do
		g=$m"g"$i
		# build for each group with group id $i
		for j in $(seq 1 $(($podNum / 2)) ); do
			# build core switch c1-gi-csj
			cs=$g"cs"$j
			dpid_dec=$(( $cs_dpid_start + $(($i - 1)) * $(($podNum / 2)) + $j ))
			dpid_hex=$(dec2hex $dpid_dec)
			echo "Add core switch "$cs", dpid is : 0x"$dpid_hex" to machine "$m" group "$g
			# add core switch to ovs
			sudo ovs-vsctl add-br $cs
			sudo ovs-vsctl set-controller $cs "$masterController"
            sudo ovs-vsctl set-fail-mode $cs secure
            sudo ovs-vsctl set bridge $cs other_config:disable-in-band=true
            sudo ovs-vsctl set bridge $cs other_config:datapath-id="$dpid_hex"
            
			for k in $(seq 1 $podNum); do
		         eth=$cs"_i"$k
		         pair_eth_port=$(( $podNum / 2 + $i))
		         pair_pod_prefix="p"$k"as"$j
		         pair_machine_prefix="a1"
		         if [ $k -gt $(( $podNum / 2 )) ]; then
		             pair_machine_prefix="a2"
		         fi
		         pair_eth=$pair_machine_prefix$pair_pod_prefix"_i"$pair_eth_port
		         #echo $eth" - "$pair_eth
		         eval pair_ip=\$$pair_eth"_ip" # eval to scan this command twice to get var(eg. m1c1cs1_i1_ip) content
		         echo $pair_ip
		         #building gre tunnel for this pair
		         sudo ovs-vsctl add-port $cs $eth -- set interface $eth type=gre option:remote_ip=$pair_ip		         
		     
		    done
		done	
    done	
    echo "Core Switch Machine "$machineCount" builing complete!"
# ==========================================
# building block for Pods Server Machine
# ==========================================
elif [ "$bType" = "podsServer" ]; then
    # create podNum pods with (podNum / 2) aggregate switches and (podNum / 2) edge switches
    podStart=$(( $(($machineCount - 1)) * $(($podNum / 2)) + 1))
    podEnd=$(( $(($machineCount)) * $(($podNum / 2)) ))
    # pod id from podStart to podEnd
	for i in $(seq $podStart $podEnd ); do
		p=$m"p"$i
        # create aggregate switches and their links in each pod
        for j in $(seq 1 $(( $podNum / 2 )) ); do
        	#add aggregate switch as$j
        	as=$p"as"$j
        	dpid_dec=$(( $as_dpid_start + $(($i - 1)) * $(($podNum / 2)) + $j ))
			dpid_hex=$(dec2hex $dpid_dec)
			echo "Add aggregate switch "$as", dpid is : "$dpid_hex" to machine "$m" pod "$p
			# add aggregate switch to ovs
			sudo ovs-vsctl add-br $as
			sudo ovs-vsctl set-controller $as "$masterController"
            sudo ovs-vsctl set-fail-mode $as secure
            sudo ovs-vsctl set bridge $as other_config:disable-in-band=true
            sudo ovs-vsctl set bridge $as other_config:datapath-id="$dpid_hex"
            # create veth peer links for this aggregate switch	
            for k in $(seq 1 $(( $podNum / 2 )) ); do
                eth=$as"_i"$k
                pair_eth_port=$(( $podNum / 2 + $j))
		        pair_pod_prefix="p"$i"es"$k
		        pair_machine_prefix="a1"
		        if [ $i -gt $(( $podNum / 2 )) ]; then
		            pair_machine_prefix="a2"
		        fi
		        pair_eth=$pair_machine_prefix$pair_pod_prefix"_i"$pair_eth_port
		        #echo $eth" - "$pair_eth
		        
		        #create veth peer for this link
		        sudo ip link add $eth type veth peer name $pair_eth
                sudo ovs-vsctl add-port $as $eth
                sudo ifconfig $eth up                			        
		    done
		    # add GRE tunnel end point for this aggregate switch
		    for t in $(seq 1 $(( $podNum / 2 )) ); do
		    	tt=$(($podNum / 2 + $t))
		    	eth=$as"_i"$tt
		    	cm="c1"
		    	if [ $t -gt $(($podNum / 4)) ]; then
		    		cm="c2"
		    	fi
		    	pair_eth=$cm"g"$t"cs"$j"_i"$i
		    	#echo $eth" - "$pair_eth_port
		    	eval pair_ip=\$$pair_eth"_ip" # eval to scan this command twice to get var(eg. m1c1cs1_i1_ip) content
		        #echo $pair_ip
		    	sudo ovs-vsctl add-port $as $eth -- set interface $eth type=gre option:remote_ip=$pair_ip
		    done
        done
        # create edge switches, hosts and their links in each pod
        for j in $(seq 1 $(( $podNum / 2 )) ); do
        	#add edge switch es$j
        	es=$p"es"$j
        	dpid_dec=$(( $es_dpid_start + $(($i - 1)) * $(($podNum / 2)) + $j ))
			dpid_hex=$(dec2hex $dpid_dec)
			echo "Add edge switch "$es", dpid is : "$dpid_hex" to machine "$m" pod "$p
			# add aggregate switch to ovs
			sudo ovs-vsctl add-br $es
			sudo ovs-vsctl set-controller $es "$masterController"
            sudo ovs-vsctl set-fail-mode $es secure
            sudo ovs-vsctl set bridge $es other_config:disable-in-band=true
            sudo ovs-vsctl set bridge $es other_config:datapath-id="$dpid_hex"
            # create veth peer links for this edge switch with hosts	
            for k in $(seq 1 $(( $podNum / 2 )) ); do
                eth=$es"_i"$k
                pair_eth_port=1
                h=$(($(($i - 1)) * $(($podNum / 2)) * $(($podNum / 2)) + $(($j - 1)) * $(($podNum / 2)) + $k ))
		        pair_pod_prefix="h"$h
		        if [ $i -gt $(( $podNum / 2 )) ]; then
		            pair_machine_prefix="a2"
		        fi
		        pair_eth=$pair_pod_prefix"_eth"$pair_eth_port
		        #echo $eth" - "$pair_eth
		        
		        #create veth peer for this link
		        sudo ip link add $eth type veth peer name $pair_eth
                sudo ovs-vsctl add-port $es $eth
                sudo ifconfig $eth up
                	
            	#create each host name space and links
            	sudo ip netns add "h"$h
                sudo ip link set $pair_eth netns "h"$h
                hexpod=$(dec2hex2 $i)
                hexes=$(dec2hex2 $j)
                hexhost=$(dec2hex2 $k)
                sudo ip netns exec "h"$h ifconfig $pair_eth hw ether 00:00:00:"$hexpod":"$hexes":"$hexhost"
                sudo ip netns exec "h"$h ip addr add 10."$i"."$j"."$k"/8 dev $pair_eth
                sudo ip netns exec "h"$h ifconfig $pair_eth up                 			        
		    done		    
		    # add port to edge switch for up-link with aggregate switches
		    for t in $(seq 1 $(( $podNum / 2 )) ); do
		    	tt=$(($podNum / 2 + $t))
		    	eth=$es"_i"$tt
		    	sudo ovs-vsctl add-port $es $eth
		    	sudo ifconfig $eth up
		    done		    
		done
    done
fi


