#include "ciadpi.h"

#include <getopt.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "params.h"

/* main() ByeDPI переименован флагом компиляции (-Dmain=ciadpi_main), см. Package.swift. */
extern int ciadpi_main(int argc, char **argv);
extern int server_fd;

int ciadpi_run(int argc, char **argv)
{
    /* Те же начальные значения, что у `struct params params` в main.c. */
    memset(&params, 0, sizeof(params));
    params.await_int = 10;
    params.cache_ttl = 100800;
    params.ipv6 = 1;
    params.resolve = 1;
    params.udp = 1;
    params.max_open = 512;
    params.bfsize = 16384;
    params.baddr.in6.sin6_family = AF_INET6;
    params.laddr.in.sin_family = AF_INET;
    server_fd = -1;

    optind = 1;
#ifdef __APPLE__
    optreset = 1;
#endif
    return ciadpi_main(argc, argv);
}

void ciadpi_stop(void)
{
    int fd = server_fd;
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
    }
}
