package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

func printHelp() {
	fmt.Println("The sync client allows to connect to a server to share one folder with all connected clients.")
	fmt.Println("Options:")
	fmt.Println("  --help, -h      Print this help message and exit.")
	fmt.Println("  --debug         Enable debug mode. This sets dummy values for all needed env vars.")
	fmt.Println("Environment Variables:")
	fmt.Println("  SERVER_PORT     : The port number for the server. Debug value: 8080")
	fmt.Println("  CLIENT_FOLDER   : The path folder to be synced. Needs to exist for the server to start. Debug value: mounted-client-folder")
	fmt.Println("  API_KEY         : The API key for authentication with the server. Debug value: SUPER-SECRET-API-KEY")
	fmt.Println("  CHUNK_SIZE      : The chunk size in bytes for sending file parts. Debug value: 10000000")
	os.Exit(0)
}

func main() {
	for _, arg := range os.Args[1:] {
		if arg == "--help" || arg == "-h" {
			printHelp()
		}
	}

	serverHost := os.Getenv("SERVER_HOST")
	serverPortStr := os.Getenv("SERVER_PORT")
	pathToFolder := os.Getenv("CLIENT_FOLDER")
	apiKey := os.Getenv("API_KEY")
	chunkSizeStr := os.Getenv("CHUNK_SIZE")

	serverPort, err := strconv.Atoi(serverPortStr)
	if err != nil {
		serverPort = 0
	}

	chunkSize, err := strconv.Atoi(chunkSizeStr)
	if err != nil {
		chunkSize = 0
	}

	if pathToFolder == "" || serverHost == "" || serverPort == 0 || apiKey == "" || chunkSize == 0 {
		args := os.Args[1:]
		isDebugMode := false
		for _, arg := range args {
			if arg == "--debug" {
				isDebugMode = true
				break
			}
		}

		if isDebugMode {
			pathToFolder = "mounted-client-folder"
			serverHost = "localhost"
			serverPort = 8080
			apiKey = "SUPER-SECRET-API-KEY"
			chunkSize = 10000000
		} else {
			fmt.Println("At least one required env var was missing:")
			fmt.Println("serverHost:", serverHost)
			fmt.Println("serverPort:", serverPort)
			fmt.Println("pathToFolder:", pathToFolder)
			fmt.Println("apiKey:", "censored")
			fmt.Println("chunkSize:", chunkSize)
			os.Exit(1)
		}
	}

	socket, err := net.Dial("tcp", fmt.Sprintf("%s:%d", serverHost, serverPort))
	if err != nil {
		fmt.Println("Error connecting to server:", err)
		os.Exit(1)
	}

	frameHandler := NewFrameHandler(chunkSize, pathToFolder, socket)
	frameHandler.Init(apiKey)
}

const (
	SYNC_INIT          = 0x0
	SYNC_NEW_FILE_PATH = 0x1
	SYNC_NEW_FILE_PART = 0x2
	SYNC_NEW_FILE_END  = 0x3
	SYNC_CLOSE         = 0x8
)

type Frame struct {
	opcode  byte
	payload []byte
}

type FrameHandler struct {
	filePaths    []string
	chunkSize    int
	pathToFolder string
	socket       net.Conn
}

func NewFrameHandler(chunkSize int, pathToFolder string, socket net.Conn) *FrameHandler {
	return &FrameHandler{
		filePaths:    make([]string, 0),
		chunkSize:    chunkSize,
		pathToFolder: pathToFolder,
		socket:       socket,
	}
}

func (fh *FrameHandler) Init(apiKey string) {
	fh.InitFilePaths()
	fh.InitSocket(apiKey)
}

func (fh *FrameHandler) InitFilePaths() {
	filePaths := make([]string, 0)

	var traverseDirectory func(currentPath string)
	traverseDirectory = func(currentPath string) {
		files, err := os.ReadDir(currentPath)
		if err != nil {
			fmt.Println("Could not open dir: ", err)
			os.Exit(1)
		}

		for _, file := range files {
			filePath := filepath.Join(currentPath, file.Name())
			fileStat, err := file.Info()
			if err != nil {
				fmt.Println("Error while reading dir: ", err)
				os.Exit(1)
			}

			if fileStat.IsDir() {
				traverseDirectory(filePath)
			} else {
				filePaths = append(filePaths, strings.Join(strings.Split(filePath, "/")[1:], "/"))
			}
		}
	}

	traverseDirectory(fh.pathToFolder)
	fh.filePaths = filePaths
	fmt.Println("Client is started with the following found files:", filePaths)
}

func (fh *FrameHandler) InitSocket(apiKey string) {
	fh.socket.Write([]byte(apiKey))
	fh.sendFrame([]byte(strings.Join(fh.filePaths, "\n")), SYNC_INIT)

	for {
		fh.HandleData()
	}
}

func (fh *FrameHandler) HandleData() {
	frame := fh.readFrame()

	switch frame.opcode {
	case SYNC_INIT:
		requestedFiles := strings.Split(string(frame.payload), "\n")
		if len(requestedFiles) == 0 {
			return
		}
		fmt.Println("Files were requested:", requestedFiles)
		for _, file := range requestedFiles {
			fh.sendFile(file)
		}
	case SYNC_NEW_FILE_PATH:
		newFilePath := string(frame.payload)
		fmt.Println("Starting to receive new file:", newFilePath)
		fh.createDirectoriesRecursively(filepath.Join(fh.pathToFolder, filepath.Dir(newFilePath)))

		filePath := filepath.Join(fh.pathToFolder, newFilePath)
		file, err := os.Create(filePath)
		defer file.Close()

		if err != nil {
			fmt.Println("Error creating file:", err)
			os.Exit(1)
		}

		frame := fh.readFrame()
		for frame.opcode != SYNC_NEW_FILE_END {
			if frame.opcode != SYNC_NEW_FILE_PART {
				fmt.Println("Invalid opcode in the middle of receiving new file: ", frame.opcode)
				os.Exit(1)
			}
			_, err = file.Write(frame.payload)
			if err != nil {
				fmt.Println("Error during writing file.")
				os.Exit(1)
			}

			frame = fh.readFrame()
		}

		endFilePath := string(frame.payload)

		if newFilePath != endFilePath {
			fmt.Println("Received end signal for wrong file path: ", endFilePath)
			os.Exit(1)
		}
		fmt.Println("Fully received new file:", filePath)
		fh.filePaths = append(fh.filePaths, filePath)
	case SYNC_CLOSE:
	default:
		fh.socket.Close()
		os.Exit(1)
	}
}

func (fh *FrameHandler) readFrame() Frame {
	buf := fh.readBytes(1 + 4)
	opcode := buf[0]
	payloadLength := int(binary.LittleEndian.Uint32(buf[1:]))
	dataBuf := fh.readBytes(payloadLength)
	return Frame{opcode: opcode, payload: dataBuf}
}

func (fh *FrameHandler) readBytes(len int) []byte {
	buf := make([]byte, len)
	count := 0
	tempBuf := buf
	for count != len {
		bytesRead, err := fh.socket.Read(tempBuf[count:])
		count += bytesRead
		if err != nil {
			fmt.Println("Error during reading frame: ", err)
			os.Exit(1)
		}
	}
	return buf
}

func (fh *FrameHandler) sendFrame(data []byte, syncOpcode byte) {
	length := make([]byte, 4)
	binary.LittleEndian.PutUint32(length, uint32(len(data)))
	payload := append([]byte{syncOpcode}, length...)
	payload = append(payload, data...)
	fh.socket.Write(payload)
}

func (fh *FrameHandler) sendFile(requestedFile string) {
	fmt.Println("Starting to send file", requestedFile)
	pathBuffer := []byte(requestedFile)
	fh.sendFrame(pathBuffer, SYNC_NEW_FILE_PATH)
	readStream, err := os.Open(filepath.Join(fh.pathToFolder, requestedFile))
	if err != nil {
		fmt.Println("Error opening file:", err)
		return
	}
	defer readStream.Close()

	buf := make([]byte, fh.chunkSize)
	var bytesRead int
	for {
		bytesRead, err = readStream.Read(buf)
		if err != nil {
			if err != io.EOF {
				fmt.Println("Error reading file:", err)
			}
			break
		}
		if bytesRead > 0 {
			fh.sendFrame(buf[:bytesRead], SYNC_NEW_FILE_PART)
		}
	}

	fh.sendFrame(pathBuffer, SYNC_NEW_FILE_END)
}

func (fh *FrameHandler) createDirectoriesRecursively(dirPath string) {
	parentDir := filepath.Dir(dirPath)

	if _, err := os.Stat(parentDir); os.IsNotExist(err) {
		fh.createDirectoriesRecursively(parentDir)
	}

	if _, err := os.Stat(dirPath); os.IsNotExist(err) {
		os.Mkdir(dirPath, os.ModePerm)
	}
}
