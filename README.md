# Hbase 2.1.0 with rdma

> by Gengyu Rao, Recolic, imzhwk

This project is for cs267.
[RDMA library for hbase](https://github.com/recolic/infinity),
is also part of the project.


# Documentations
I will upload it [here](https://recolic.net/tmp/res/cs267/doc.pdf) later. Please
feel free to ask me any question about the code.

# Current status
On master branch, we are using kind of sequencial connection, which is very slow but mostly debugged and tested.

On parallel_conn branch, one thread may be reading while the other is writing to the connection.
Commits after tag 0.1.4 are trying to optimize it, you can just drop those
commits.

