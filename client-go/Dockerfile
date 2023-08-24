FROM golang as builder
WORKDIR /go/src/app
COPY . .
RUN go build -ldflags="-s -w" -o /go/bin/app
FROM scratch
COPY --from=builder /go/bin/app /
CMD [ "/app" ]

# FROM golang:1.19
# RUN mkdir /app
# COPY ./main.go /app/
# COPY ./go.mod /app
# WORKDIR /app
# RUN go install && go build

# ENTRYPOINT ["client-go"]