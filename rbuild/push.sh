#!/bin/bash

rsync -rh --progress bin root@mc.recolic.net:/var/www/html/tmp &&
ssh root@mc.recolic.net 'cd /var/www/html/tmp ; and tar -cvzf bin.tar.gz bin/' &&
ssh ubuntu@drive.recolic.net './rdma.sh "cd ~/tmp && mcget.sh bin.tar.gz -O ./bin.tar.gz && rm -rf bin ; rm -rf bin && decomp bin.tar.gz"'

