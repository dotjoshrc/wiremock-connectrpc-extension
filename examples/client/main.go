package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"time"

	"connectrpc.com/connect"
	examplev1 "github.com/dotjoshrc/wiremock-connectrpc-extension/examples/client/gen/example/v1"
	"github.com/dotjoshrc/wiremock-connectrpc-extension/examples/client/gen/example/v1/examplev1connect"
)

func main() {
	expectedError := flag.String("expect-error", "", "expected Connect error code instead of a successful greeting")
	flag.Parse()
	args := flag.Args()
	baseURL := "http://localhost:8080"
	if len(args) > 0 {
		baseURL = args[0]
	}
	encoding := "json"
	if len(args) > 1 {
		encoding = args[1]
	}
	var options []connect.ClientOption
	switch encoding {
	case "json":
		options = append(options, connect.WithProtoJSON())
	case "proto":
	default:
		log.Fatalf("unsupported encoding %q: use json or proto", encoding)
	}
	client := examplev1connect.NewGreeterServiceClient(http.DefaultClient, baseURL, options...)
	name := "Josh"
	if len(args) > 2 {
		name = args[2]
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	response, err := client.Greet(ctx, connect.NewRequest(&examplev1.GreetRequest{Name: name}))
	if *expectedError != "" {
		if err == nil || connect.CodeOf(err).String() != *expectedError {
			log.Fatalf("expected Connect error %q, got %v", *expectedError, err)
		}
		fmt.Println(err)
		return
	}
	if err != nil {
		log.Fatal(err)
	}
	if response.Msg.Greeting != "Hello, Josh!" {
		log.Fatalf("unexpected greeting: %q", response.Msg.Greeting)
	}
	fmt.Println(response.Msg.Greeting)
}
