#!/bin/bash
ID='-i /home/rgy/.ssh/aliyun/id_rsa -P 25568  rdma_match@proxy.recolic.net'
#ID=' -i ~/.ssh/aliyun/id_rsa  rdma_match@202.114.10.172'

cd ./rbuild/bin/lib
echo "
cd ./hbase-2.1.0/lib/
ls
lls
put ./*.jar
"|sftp $ID
echo done.

