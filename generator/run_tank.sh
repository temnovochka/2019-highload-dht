docker run \
    -v $(pwd):/var/loadtest \
    -v $HOME/.ssh:/root/.ssh \
    --net host \
    -it direvius/yandex-tank \
    -c $1
